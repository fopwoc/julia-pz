package dev.julia.probe.host;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;

/** Replaces only the SDL backend's display queries; GLFW and its input implementation remain game-owned. */
final class WindowBindings {
    private static final String ROOT = "com.gtnewhorizons.angelica.sdlgpu.";

    private WindowBindings() { }

    static byte[] adapt(String name, byte[] bytes) {
        if (!name.equals(ROOT + "device.Device") && !name.equals(ROOT + "SDLGPURenderBackend")) return bytes;
        var node = new ClassNode();
        new ClassReader(bytes).accept(node, 0);
        boolean changed = false;
        for (var method : node.methods) {
            for (var instruction : method.instructions) {
                if (instruction instanceof MethodInsnNode call && call.owner.equals("org/lwjglx/opengl/Display")
                        && switch (call.name + call.desc) {
                            case "getWindow()J", "getWidth()I", "getHeight()I", "wasResized()Z" -> true;
                            default -> false;
                        }) {
                    call.owner = "dev/julia/probe/window/HostWindow";
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
