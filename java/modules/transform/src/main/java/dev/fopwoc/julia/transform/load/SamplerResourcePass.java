package dev.fopwoc.julia.transform.load;

import static dev.fopwoc.julia.backend.texture.TextureBindings.UNIT_COUNT;
import static org.lwjgl.opengl.GL20.GL_MAX_COMBINED_TEXTURE_IMAGE_UNITS;
import static org.lwjgl.opengl.GL20.GL_MAX_TEXTURE_IMAGE_UNITS;

import dev.fopwoc.julia.transform.Endpoint;
import java.util.Set;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

/** Version-pinned sampler table capacity, resource-array widths and indirect MSL compilation. */
public final class SamplerResourcePass {
    private static final String ROOT = "com.gtnewhorizons.angelica.sdlgpu.";
    private static final Set<String> TABLES = Set.of(
            "fragSamplerBindings",
            "vertSamplerBindings",
            "lastFragSamplerTex",
            "lastFragSamplerSmp",
            "lastVertSamplerTex",
            "lastVertSamplerSmp");

    private SamplerResourcePass() {}

    public static byte[] adapt(String name, byte[] bytes) {
        boolean replay = name.equals(ROOT + "compute.VertexWriteReplayer");
        boolean replayLayout = name.equals(ROOT + "compute.VertexWriteReplayer$Replay");
        boolean glsm = name.equals("com.gtnewhorizons.angelica.glsm.GLStateManager");
        boolean manager = name.equals(ROOT + "shader.ShaderManager");
        boolean context = name.equals(ROOT + "frame.ContextState");
        boolean binder = name.equals(ROOT + "sampler.SamplerBinder");
        boolean msl = name.equals(ROOT + "shader.msl.MslCrossCompile");
        boolean clears = name.equals(ROOT + "resource.FBOClearTracker");
        boolean overflow = name.equals(ROOT + "shader.SamplerOverflow");
        if (!replay && !replayLayout && !glsm && !manager && !context && !binder && !msl && !clears && !overflow)
            return bytes;
        var node = new ClassNode();
        new ClassReader(bytes).accept(node, 0);
        int edits = 0;
        for (var field : node.fields) {
            if (replay && field.name.equals("TEXTURE_UNITS")) {
                if (!Integer.valueOf(32).equals(field.value))
                    throw new IllegalStateException("Pinned replay unit limit changed");
                field.value = UNIT_COUNT;
                edits++;
            }
            if ((context && field.name.equals("MAX_SAMPLERS")) || (overflow && field.name.equals("SAMPLER_SLOTS"))) {
                if (!Integer.valueOf(16).equals(field.value))
                    throw new IllegalStateException("Pinned sampler constant changed");
                field.value = 32;
                edits++;
            }
        }
        for (var method : node.methods) {
            if (replay && method.name.equals("<init>")) {
                for (var instruction : method.instructions.toArray())
                    if (instruction instanceof FieldInsnNode field
                            && field.getOpcode() == Opcodes.PUTFIELD
                            && Set.of("samplerOverrides", "savedTextures").contains(field.name)) {
                        var size = field.getPrevious().getPrevious();
                        if (!(size instanceof IntInsnNode value) || value.operand != 32)
                            throw new IllegalStateException("Pinned replay unit allocation changed");
                        method.instructions.set(size, new IntInsnNode(Opcodes.SIPUSH, UNIT_COUNT));
                        edits++;
                    }
            }
            if (replayLayout && method.name.equals("resolveSamplers")) {
                for (var instruction : method.instructions.toArray())
                    if (instruction instanceof IntInsnNode value && value.operand == 31) {
                        method.instructions.set(value, new IntInsnNode(Opcodes.BIPUSH, UNIT_COUNT - 1));
                        edits++;
                    }
            }
            if (glsm && method.name.equals("<clinit>")) {
                for (var instruction : method.instructions.toArray())
                    if (instruction instanceof LdcInsnNode value
                            && Integer.valueOf(GL_MAX_TEXTURE_IMAGE_UNITS).equals(value.cst)) {
                        value.cst = GL_MAX_COMBINED_TEXTURE_IMAGE_UNITS;
                        edits++;
                    }
            }
            if (manager
                    && (method.name.equals("collectResourceIds")
                            || method.name.equals("collectSamplerEntries")
                            || method.name.equals("slotBudgetError")
                            || method.name.equals("registerSamplerLocations"))) {
                String target = switch (method.name) {
                    case "collectResourceIds" -> "remap";
                    case "collectSamplerEntries" -> "entries";
                    case "registerSamplerLocations" -> "register";
                    default -> "budget";
                };
                method.instructions.clear();
                method.tryCatchBlocks.clear();
                if (method.localVariables != null) method.localVariables.clear();
                int slot = 0;
                for (var type : Type.getArgumentTypes(method.desc)) {
                    method.instructions.add(new VarInsnNode(type.getOpcode(Opcodes.ILOAD), slot));
                    slot += type.getSize();
                }
                method.instructions.add(new MethodInsnNode(
                        Opcodes.INVOKESTATIC, Endpoint.SAMPLER_RESOURCES.owner(), target, method.desc, false));
                method.instructions.add(
                        new InsnNode(Type.getReturnType(method.desc).getOpcode(Opcodes.IRETURN)));
                edits++;
            }
            if (manager && method.name.equals("remapSpirvForComputeSDLGPU")) {
                var head = new InsnList();
                head.add(new VarInsnNode(Opcodes.ALOAD, 0));
                head.add(new MethodInsnNode(
                        Opcodes.INVOKESTATIC,
                        Endpoint.SAMPLER_SHADER_BRIDGE.owner(),
                        "requireComputeLayout",
                        "(Ljava/nio/ByteBuffer;)V",
                        false));
                method.instructions.insert(head);
                edits++;
            }
            if (manager)
                for (var instruction : method.instructions.toArray()) {
                    if (instruction instanceof LdcInsnNode value
                            && value.cst instanceof String text
                            && Set.of("sdl-stage", "sdl-prewarm", "sdl-vertex-variant")
                                    .contains(text)) {
                        value.cst = "julia-samplers-v2-" + text;
                        edits++;
                    }
                }
            if (context && method.name.equals("<init>")) {
                for (var instruction : method.instructions.toArray()) {
                    if (!(instruction instanceof FieldInsnNode field) || field.getOpcode() != Opcodes.PUTFIELD)
                        continue;
                    boolean logical = field.name.equals("boundTextures") || field.name.equals("boundSamplerObjects");
                    if (!logical && !TABLES.contains(field.name)) continue;
                    var value = field.getPrevious();
                    if (value instanceof IntInsnNode array && array.getOpcode() == Opcodes.NEWARRAY)
                        value = value.getPrevious();
                    else if (value instanceof MethodInsnNode call && call.name.equals("calloc"))
                        value = value.getPrevious();
                    else throw new IllegalStateException("Pinned sampler allocation changed: " + field.name);
                    if (!(value instanceof IntInsnNode constant) || constant.operand != (logical ? 32 : 16))
                        throw new IllegalStateException("Pinned sampler capacity changed: " + field.name);
                    if (logical) method.instructions.set(constant, new IntInsnNode(Opcodes.SIPUSH, UNIT_COUNT));
                    else constant.operand = 32;
                    edits++;
                }
            }
            if (binder && (method.name.equals("bindStageSamplers") || method.name.equals("bindFallbackForStage"))) {
                for (var instruction : method.instructions.toArray())
                    if (instruction instanceof IntInsnNode constant
                            && constant.operand == 16
                            && constant.getOpcode() == Opcodes.BIPUSH) {
                        constant.operand = 32;
                        edits++;
                    }
            }
            if (clears && method.name.equals("flushPendingClearsForBoundSamplers")
                    || overflow && method.name.equals("collectEdits")) {
                for (var instruction : method.instructions.toArray())
                    if (instruction instanceof IntInsnNode constant
                            && constant.operand == 16
                            && constant.getOpcode() == Opcodes.BIPUSH) {
                        constant.operand = 32;
                        edits++;
                    }
            }
            if (msl && method.name.equals("compile") && method.desc.startsWith("(Ljava/nio/ByteBuffer;IZ)")) {
                var head = new InsnList();
                var original = new LabelNode();
                head.add(new VarInsnNode(Opcodes.ALOAD, 0));
                head.add(new VarInsnNode(Opcodes.ILOAD, 1));
                head.add(new MethodInsnNode(
                        Opcodes.INVOKESTATIC,
                        Endpoint.INSTANCE_INDEX_SPIRV.owner(),
                        "translate",
                        "(Ljava/nio/ByteBuffer;I)Ljava/nio/ByteBuffer;",
                        false));
                head.add(new VarInsnNode(Opcodes.ASTORE, 0));
                head.add(new VarInsnNode(Opcodes.ALOAD, 0));
                head.add(new VarInsnNode(Opcodes.ILOAD, 1));
                head.add(new MethodInsnNode(
                        Opcodes.INVOKESTATIC,
                        Endpoint.SAMPLER_SHADER_BRIDGE.owner(),
                        "needsArguments",
                        "(Ljava/nio/ByteBuffer;I)Z",
                        false));
                head.add(new JumpInsnNode(Opcodes.IFEQ, original));
                head.add(new VarInsnNode(Opcodes.ALOAD, 0));
                head.add(new VarInsnNode(Opcodes.ILOAD, 1));
                head.add(new MethodInsnNode(
                        Opcodes.INVOKESTATIC,
                        Endpoint.SAMPLER_SHADER_BRIDGE.owner(),
                        "compile",
                        "(Ljava/nio/ByteBuffer;I)Lcom/gtnewhorizons/angelica/sdlgpu/shader/cross/CrossCompileCache$Output;",
                        false));
                head.add(new InsnNode(Opcodes.ARETURN));
                head.add(original);
                head.add(new FrameNode(Opcodes.F_SAME, 0, null, 0, null));
                method.instructions.insert(head);
                edits++;
            }
        }
        int expected = replay
                ? 3
                : replayLayout ? 1 : glsm ? 1 : manager ? 8 : context ? 9 : binder ? 4 : clears || overflow ? 2 : 1;
        if (edits != expected)
            throw new IllegalStateException(
                    "Pinned sampler integration changed: " + name + " anchors=" + edits + " expected=" + expected);
        var writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        node.accept(writer);
        return writer.toByteArray();
    }
}
