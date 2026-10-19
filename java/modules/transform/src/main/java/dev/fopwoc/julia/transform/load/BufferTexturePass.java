package dev.fopwoc.julia.transform.load;

import static org.lwjgl.opengl.GL31.GL_TEXTURE_BUFFER;

import dev.fopwoc.julia.transform.Endpoint;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;

/** Pinned integration with SDL sampler binding and buffer-store retirement; no mod-specific behavior. */
public final class BufferTexturePass {
    private static final String ROOT = "com.gtnewhorizons.angelica.sdlgpu.";
    private static final String STATE = ROOT.replace('.', '/') + "frame/ContextState";
    private static final String RESOURCE = ROOT.replace('.', '/') + "resource/ResourceManager";

    private BufferTexturePass() {}

    public static byte[] adapt(String name, byte[] bytes) {
        boolean context = name.equals(ROOT + "frame.ContextState");
        boolean resource = name.equals(ROOT + "resource.ResourceManager");
        boolean samplers = name.equals(ROOT + "sampler.SamplerBinder");
        boolean compute = name.equals(ROOT + "compute.ComputeBinder");
        boolean msl = name.equals(ROOT + "shader.msl.MslCrossCompile");
        if (!context && !resource && !samplers && !compute && !msl) return bytes;
        var node = new ClassNode();
        new ClassReader(bytes).accept(node, 0);
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
            for (var instruction : method.instructions.toArray()) {
                if (samplers
                        && instruction instanceof MethodInsnNode invoke
                        && invoke.owner.equals(STATE)
                        && invoke.name.equals("boundTexture")
                        && invoke.desc.equals("(I)I")) {
                    method.instructions.set(invoke, call("samplerBinding", "(L" + STATE + ";I)I"));
                    edits++;
                }
                if (compute && method.name.equals("bindComputeSamplers")) {
                    if (instruction instanceof FieldInsnNode field
                            && field.owner.equals(STATE)
                            && field.name.equals("boundTextures")) {
                        var read = field.getNext();
                        while (read != null && read.getOpcode() != Opcodes.IALOAD) read = read.getNext();
                        if (read == null) throw new IllegalStateException("Compute sampler texture-array read changed");
                        method.instructions.remove(field);
                        method.instructions.set(read, call("samplerBinding", "(L" + STATE + ";I)I"));
                        edits++;
                    }
                    if (instruction instanceof MethodInsnNode invoke
                            && invoke.owner.equals(RESOURCE)
                            && invoke.name.equals("getTextureHandle")
                            && invoke.desc.equals("(I)J")) {
                        method.instructions.set(invoke, call("samplingHandle", "(L" + RESOURCE + ";I)J"));
                        edits++;
                    }
                }
                if (msl
                        && instruction instanceof MethodInsnNode invoke
                        && invoke.owner.equals("org/lwjgl/util/spvc/Spvc")
                        && invoke.name.equals("spvc_compiler_install_compiler_options")
                        && invoke.desc.equals("(JJ)I")) {
                    method.instructions.set(invoke, call("installMslOptions", "(JJ)I"));
                    edits++;
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
        int expected = compute ? 2 : msl ? 3 : 1;
        if (edits != expected)
            throw new IllegalStateException(
                    "Buffer-texture integration changed: " + name + " has " + edits + " anchors, expected " + expected);
        var writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        node.accept(writer);
        return writer.toByteArray();
    }

    private static MethodInsnNode call(String name, String descriptor) {
        return new MethodInsnNode(Opcodes.INVOKESTATIC, Endpoint.BUFFER_TEXTURE_HOOKS.owner(), name, descriptor, false);
    }
}
