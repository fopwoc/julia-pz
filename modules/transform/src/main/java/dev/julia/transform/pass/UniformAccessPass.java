package dev.julia.transform.pass;

import dev.julia.transform.Endpoint;
import java.util.Set;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;

/**
 * Routes uniform reads around the published backend's empty {@code getUniform} defaults, and integer-array uploads
 * to the bridge that respects sampler and boolean locations.
 */
public final class UniformAccessPass {
    private static final Set<String> NATIVE_QUERIES = Set.of(
            "glGetUniformf(II)F",
            "glGetUniformi(II)I",
            "glGetUniformfv(IILjava/nio/FloatBuffer;)V",
            "glGetUniformfv(II[F)V",
            "glGetUniformiv(IILjava/nio/IntBuffer;)V",
            "glGetUniformiv(II[I)V");

    private UniformAccessPass() {}

    public static void adapt(ClassNode node) {
        String query = Endpoint.UNIFORM_QUERY_BRIDGE.owner();
        for (var method : node.methods) {
            for (var instruction : method.instructions) {
                if (!(instruction instanceof MethodInsnNode call) || call.getOpcode() != Opcodes.INVOKESTATIC) continue;
                String signature = call.name + call.desc;
                if (call.owner.equals("org/lwjgl/opengl/GL43") && signature.equals("glUniform1iv(I[I)V")) {
                    call.owner = Endpoint.UNIFORM_UPLOAD_BRIDGE.owner();
                } else if (Endpoint.STATE_MANAGER.is(call.owner) && call.name.equals("glGetUniform")) {
                    String name = switch (call.desc) {
                        case "(IILjava/nio/FloatBuffer;)V" -> "glGetUniformfv";
                        case "(IILjava/nio/IntBuffer;)V" -> "glGetUniformiv";
                        default -> null;
                    };
                    if (name != null) {
                        call.owner = query;
                        call.name = name;
                    }
                } else if ((call.owner.equals("org/lwjgl/opengl/GL20") || call.owner.equals("org/lwjgl/opengl/GL43"))
                        && NATIVE_QUERIES.contains(signature)) {
                    call.owner = query;
                }
            }
        }
    }
}
