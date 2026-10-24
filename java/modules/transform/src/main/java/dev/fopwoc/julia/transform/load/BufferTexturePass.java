package dev.fopwoc.julia.transform.load;

import static org.lwjgl.opengl.GL31.GL_TEXTURE_BUFFER;

import dev.fopwoc.julia.transform.Endpoint;
import dev.fopwoc.julia.transform.asm.Anchors;
import java.util.Set;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.*;

/** Pinned integration with SDL sampler binding and buffer-store retirement; no mod-specific behavior. */
public final class BufferTexturePass implements UpstreamPatch {
    private static final String ROOT = "com.gtnewhorizons.angelica.sdlgpu.";
    private static final Set<String> TARGETS = Set.of(
            ROOT + "frame.ContextState",
            ROOT + "resource.ResourceManager",
            ROOT + "sampler.SamplerBinder",
            ROOT + "compute.ComputeBinder",
            ROOT + "shader.msl.MslCrossCompile");
    private static final String STATE = ROOT.replace('.', '/') + "frame/ContextState";
    private static final String RESOURCE = ROOT.replace('.', '/') + "resource/ResourceManager";

    @Override
    public boolean targets(String name) {
        return TARGETS.contains(name);
    }

    @Override
    public boolean apply(String name, ClassNode node) {
        boolean context = name.equals(ROOT + "frame.ContextState");
        boolean resource = name.equals(ROOT + "resource.ResourceManager");
        boolean samplers = name.equals(ROOT + "sampler.SamplerBinder");
        boolean compute = name.equals(ROOT + "compute.ComputeBinder");
        boolean msl = name.equals(ROOT + "shader.msl.MslCrossCompile");
        int edits = 0;
        for (var method : node.methods) {
            if (context && method.name.equals("getBoundBuffer") && method.desc.equals("(I)I")) {
                var head = new InsnList();
                var next = new LabelNode();
                head.add(new VarInsnNode(Opcodes.ILOAD, 1));
                head.add(new LdcInsnNode(GL_TEXTURE_BUFFER));
                head.add(new JumpInsnNode(Opcodes.IF_ICMPNE, next));
                head.add(call("boundBuffer", "()I"));
                head.add(new InsnNode(Opcodes.IRETURN));
                head.add(next);
                head.add(new FrameNode(Opcodes.F_SAME, 0, null, 0, null));
                method.instructions.insert(head);
                edits++;
            }
            if (resource && method.name.equals("returnBufferToPool") && method.desc.equals("(JIJ)V")) {
                var head = new InsnList();
                var next = new LabelNode();
                head.add(new VarInsnNode(Opcodes.ALOAD, 0));
                head.add(new VarInsnNode(Opcodes.LLOAD, 1));
                head.add(call("retire", "(L" + RESOURCE + ";J)Z"));
                head.add(new JumpInsnNode(Opcodes.IFEQ, next));
                head.add(new InsnNode(Opcodes.RETURN));
                head.add(next);
                head.add(new FrameNode(Opcodes.F_SAME, 0, null, 0, null));
                method.instructions.insert(head);
                edits++;
            }
            if (compute && method.name.equals("bindComputeSamplers"))
                edits += Anchors.replaceCalls(
                        method,
                        RESOURCE,
                        "getTextureHandle",
                        "(I)J",
                        () -> call("samplingHandle", "(L" + RESOURCE + ";I)J"));
            if (msl)
                edits += Anchors.replaceCalls(
                        method,
                        "org/lwjgl/util/spvc/Spvc",
                        "spvc_compiler_install_compiler_options",
                        "(JJ)I",
                        () -> call("installMslOptions", "(JJ)I"));
            for (var instruction : method.instructions.toArray()) {
                if (samplers
                        && instruction instanceof MethodInsnNode invoke
                        && invoke.owner.equals(STATE)
                        && invoke.name.equals("boundTexture")
                        && invoke.desc.equals("(I)I")) {
                    stageSlot(method, invoke);
                    method.instructions.set(invoke, call("samplerBinding", "(L" + STATE + ";IIZ)I"));
                    edits++;
                }
                if (compute && method.name.equals("bindComputeSamplers")) {
                    if (instruction instanceof FieldInsnNode field
                            && field.owner.equals(STATE)
                            && field.name.equals("boundTextures")) {
                        var read = field.getNext();
                        while (read != null && read.getOpcode() != Opcodes.IALOAD) read = read.getNext();
                        if (read == null) throw new IllegalStateException("Compute sampler texture-array read changed");
                        if (!(Anchors.previous(field) instanceof VarInsnNode state)
                                || !(Anchors.previous(state) instanceof VarInsnNode unitStore)
                                || unitStore.getOpcode() != Opcodes.ISTORE
                                || !(Anchors.previous(unitStore) instanceof MethodInsnNode resolve)
                                || !resolve.name.equals("resolveUnit")
                                || Anchors.previous(resolve).getOpcode() != Opcodes.IALOAD
                                || !(Anchors.previous(Anchors.previous(resolve)) instanceof VarInsnNode slot)
                                || slot.getOpcode() != Opcodes.ILOAD
                                || !method.desc.equals(
                                        "(L" + STATE + ";L" + ROOT.replace('.', '/')
                                                + "shader/ShaderManager$ProgramObject;J[I[Ljava/lang/String;Lorg/lwjgl/system/MemoryStack;)Z"))
                            throw new IllegalStateException("Compute sampler identity lookup changed");
                        var identity = new InsnList();
                        identity.add(new VarInsnNode(Opcodes.ILOAD, slot.var));
                        identity.add(new VarInsnNode(Opcodes.ALOAD, 6));
                        method.instructions.insertBefore(read, identity);
                        method.instructions.remove(field);
                        method.instructions.set(
                                read, call("computeSamplerBinding", "(L" + STATE + ";II[Ljava/lang/String;)I"));
                        edits++;
                    }
                }
                if (msl
                        && method.name.equals("<clinit>")
                        && instruction instanceof LdcInsnNode value
                        && (value.cst.equals("msl") || value.cst.equals("msl31"))) {
                    value.cst = "julia-buffer-" + value.cst + "-v1";
                    edits++;
                }
            }
        }
        Anchors.require("buffer-texture integration in " + name, edits, compute ? 2 : msl ? 3 : 1);
        return true;
    }

    /**
     * Passes the backend slot and stage so a unit shared by several sampler types resolves per sampler. Upstream reads
     * {@code unit = units[slot]} into a local just before the lookup, and the stage is the method's boolean parameter.
     */
    private static void stageSlot(MethodNode method, MethodInsnNode invoke) {
        if (!method.name.equals("bindStageSamplers"))
            throw new IllegalStateException("Sampler lookup moved out of bindStageSamplers");
        var arguments = Type.getArgumentTypes(method.desc);
        if (arguments.length == 0 || arguments[arguments.length - 1] != Type.BOOLEAN_TYPE)
            throw new IllegalStateException("Sampler stage parameter changed: " + method.desc);
        int fragment = (Type.getArgumentsAndReturnSizes(method.desc) >> 2) - 1;
        if (!(Anchors.previous(invoke) instanceof VarInsnNode unit && unit.getOpcode() == Opcodes.ILOAD)
                || !(Anchors.previous(unit) instanceof VarInsnNode state && state.getOpcode() == Opcodes.ALOAD)
                || !(Anchors.previous(state) instanceof VarInsnNode store
                        && store.getOpcode() == Opcodes.ISTORE
                        && store.var == unit.var)
                || !(Anchors.previous(store) instanceof InsnNode element && element.getOpcode() == Opcodes.IALOAD)
                || !(Anchors.previous(element) instanceof VarInsnNode slot && slot.getOpcode() == Opcodes.ILOAD))
            throw new IllegalStateException("Sampler slot lookup changed in bindStageSamplers");
        var load = new InsnList();
        load.add(new VarInsnNode(Opcodes.ILOAD, slot.var));
        load.add(new VarInsnNode(Opcodes.ILOAD, fragment));
        method.instructions.insertBefore(invoke, load);
    }

    private static MethodInsnNode call(String name, String descriptor) {
        return new MethodInsnNode(Opcodes.INVOKESTATIC, Endpoint.BUFFER_TEXTURE_HOOKS.owner(), name, descriptor, false);
    }
}
