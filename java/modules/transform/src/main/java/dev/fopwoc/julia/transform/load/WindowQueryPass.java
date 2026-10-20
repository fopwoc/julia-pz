package dev.fopwoc.julia.transform.load;

import java.util.Set;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;

/**
 * Points the SDL backend's display queries at Julia's borrowed-window binding. GLFW and its input implementation stay
 * game-owned; nothing else in {@code Display} is touched.
 */
public final class WindowQueryPass implements UpstreamPatch {
    /** Platform-independent binding of the borrowed SDL window. */
    public static final String HOST_WINDOW = "dev/fopwoc/julia/backend/window/HostWindow";

    private static final String ROOT = "com.gtnewhorizons.angelica.sdlgpu.";
    private static final Set<String> CLASSES = Set.of(ROOT + "device.Device", ROOT + "SDLGPURenderBackend");
    private static final String DISPLAY = "org/lwjglx/opengl/Display";
    private static final Set<String> QUERIES = Set.of("getWindow()J", "getWidth()I", "getHeight()I", "wasResized()Z");

    @Override
    public boolean targets(String className) {
        return CLASSES.contains(className);
    }

    @Override
    public boolean apply(String className, ClassNode node) {
        boolean changed = false;
        for (var method : node.methods) {
            for (var instruction : method.instructions.toArray()) {
                if (className.equals(ROOT + "device.Device")
                        && method.name.equals("getWindowSizeInPixels")
                        && method.desc.equals("()J")
                        && instruction.getOpcode() == Opcodes.LRETURN) {
                    method.instructions.insertBefore(
                            instruction,
                            new MethodInsnNode(Opcodes.INVOKESTATIC, HOST_WINDOW, "renderSize", "(J)J", false));
                    changed = true;
                }
                if (instruction instanceof MethodInsnNode call
                        && call.owner.equals(DISPLAY)
                        && QUERIES.contains(call.name + call.desc)) {
                    call.owner = HOST_WINDOW;
                    changed = true;
                }
            }
        }
        return changed;
    }
}
