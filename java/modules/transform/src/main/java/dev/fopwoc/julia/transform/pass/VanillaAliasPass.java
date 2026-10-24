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
    // Keyed by function, so every core version class a caller imports from reaches the same alias.
    private static final Map<String, String> ALIASES = Map.of(
            CoreOwners.core("glLightfv(IILjava/nio/FloatBuffer;)V"), "glLight",
            CoreOwners.core("glMaterialfv(IILjava/nio/FloatBuffer;)V"), "glMaterial",
            CoreOwners.core("glMultMatrixf(Ljava/nio/FloatBuffer;)V"), "glMultMatrix",
            CoreOwners.core("glUniform2fv(ILjava/nio/FloatBuffer;)V"), "glUniform2",
            CoreOwners.core("glGetBooleanv(ILjava/nio/ByteBuffer;)V"), "glGetBoolean");

    private VanillaAliasPass() {}

    public static void adapt(ClassNode node) {
        for (var method : node.methods) {
            for (var instruction : method.instructions.toArray()) {
                if (!(instruction instanceof MethodInsnNode call) || call.getOpcode() != Opcodes.INVOKESTATIC) continue;
                String key = CoreOwners.key(call.owner, call.name, call.desc);
                if (key.equals(GLSM + ".glDepthRange(DD)V")) {
                    call.owner = Endpoint.DEPTH_RANGE_BRIDGE.owner();
                } else if (key.equals(CoreOwners.core("glClearDepthf(F)V"))) {
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
