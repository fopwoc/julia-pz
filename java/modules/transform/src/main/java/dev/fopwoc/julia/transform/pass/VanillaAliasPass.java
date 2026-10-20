package dev.fopwoc.julia.transform.pass;

import dev.fopwoc.julia.transform.Endpoint;
import java.util.Map;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodInsnNode;

/** Exact remaining vanilla entry points with implemented GLSM/backend equivalents. */
public final class VanillaAliasPass {
    private static final String GLSM = Endpoint.STATE_MANAGER.owner();
    private static final Map<String, String> ALIASES = Map.of(
            "org/lwjgl/opengl/GL11.glLightfv(IILjava/nio/FloatBuffer;)V", "glLight",
            "org/lwjgl/opengl/GL11.glMaterialfv(IILjava/nio/FloatBuffer;)V", "glMaterial",
            "org/lwjgl/opengl/GL11.glMultMatrixf(Ljava/nio/FloatBuffer;)V", "glMultMatrix",
            "org/lwjgl/opengl/GL43.glUniform2fv(ILjava/nio/FloatBuffer;)V", "glUniform2");

    private VanillaAliasPass() {}

    public static void adapt(ClassNode node) {
        for (var method : node.methods) {
            for (var instruction : method.instructions.toArray()) {
                if (!(instruction instanceof MethodInsnNode call) || call.getOpcode() != Opcodes.INVOKESTATIC) continue;
                String key = call.owner + "." + call.name + call.desc;
                if (key.equals(GLSM + ".glDepthRange(DD)V")) {
                    call.owner = Endpoint.DEPTH_RANGE_BRIDGE.owner();
                } else if (key.equals("org/lwjgl/opengl/GL43.glClearDepthf(F)V")) {
                    method.instructions.insertBefore(call, new InsnNode(Opcodes.F2D));
                    call.owner = GLSM;
                    call.name = "glClearDepth";
                    call.desc = "(D)V";
                } else {
                    String name = ALIASES.get(key);
                    if (name != null) {
                        call.owner = GLSM;
                        call.name = name;
                    }
                }
            }
        }
    }
}
