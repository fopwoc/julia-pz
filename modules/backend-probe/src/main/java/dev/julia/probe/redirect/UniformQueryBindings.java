package dev.julia.probe.redirect;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;

/** Exact read overloads route around the published backend's empty getUniform defaults. */
public final class UniformQueryBindings {
    public static final String OWNER = "dev/julia/probe/adapter/UniformQueryBridge";
    private UniformQueryBindings() { }

    public static void adapt(ClassNode node) {
        for (var method : node.methods) {
            for (var instruction : method.instructions) {
                if (!(instruction instanceof MethodInsnNode call) || call.getOpcode() != Opcodes.INVOKESTATIC) continue;
                if (call.owner.equals("org/lwjgl/opengl/GL43") && call.name.equals("glUniform1iv") && call.desc.equals("(I[I)V")) {
                    call.owner = "dev/julia/probe/adapter/UniformUploadBridge";
                } else if (call.owner.equals("com/gtnewhorizons/angelica/glsm/GLStateManager") && call.name.equals("glGetUniform")) {
                    String name = switch (call.desc) {
                        case "(IILjava/nio/FloatBuffer;)V" -> "glGetUniformfv";
                        case "(IILjava/nio/IntBuffer;)V" -> "glGetUniformiv";
                        default -> null;
                    };
                    if (name != null) { call.owner = OWNER; call.name = name; }
                } else if (call.owner.equals("org/lwjgl/opengl/GL20") || call.owner.equals("org/lwjgl/opengl/GL43")) {
                    boolean known = switch (call.name + call.desc) {
                        case "glGetUniformf(II)F", "glGetUniformi(II)I",
                                "glGetUniformfv(IILjava/nio/FloatBuffer;)V", "glGetUniformfv(II[F)V",
                                "glGetUniformiv(IILjava/nio/IntBuffer;)V", "glGetUniformiv(II[I)V" -> true;
                        default -> false;
                    };
                    if (known) call.owner = OWNER;
                }
            }
        }
    }
}
