package dev.julia.transform.load;

import java.util.Set;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;

/**
 * Points the SDL backend's display queries at Julia's borrowed-window binding. GLFW and its input implementation stay
 * game-owned; nothing else in {@code Display} is touched.
 */
public final class WindowQueryPass {
    /** Platform-independent binding of the borrowed SDL window. */
    public static final String HOST_WINDOW = "dev/julia/backend/window/HostWindow";

    private static final String ROOT = "com.gtnewhorizons.angelica.sdlgpu.";
    private static final Set<String> CLASSES = Set.of(ROOT + "device.Device", ROOT + "SDLGPURenderBackend");
    private static final String DISPLAY = "org/lwjglx/opengl/Display";
    private static final Set<String> QUERIES = Set.of("getWindow()J", "getWidth()I", "getHeight()I", "wasResized()Z");

    private WindowQueryPass() {}

    public static byte[] adapt(String className, byte[] bytes) {
        if (!CLASSES.contains(className)) return bytes;
        var node = new ClassNode();
        new ClassReader(bytes).accept(node, 0);
        boolean changed = false;
        for (var method : node.methods) {
            for (var instruction : method.instructions) {
                if (instruction instanceof MethodInsnNode call
                        && call.owner.equals(DISPLAY)
                        && QUERIES.contains(call.name + call.desc)) {
                    call.owner = HOST_WINDOW;
                    changed = true;
                }
            }
        }
        if (!changed) return bytes;
        var writer = new ClassWriter(0);
        node.accept(writer);
        return writer.toByteArray();
    }
}
