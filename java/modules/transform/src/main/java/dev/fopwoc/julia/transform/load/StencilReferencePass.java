package dev.fopwoc.julia.transform.load;

import dev.fopwoc.julia.transform.Endpoint;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;

/** Zero-extends unsigned stencil arguments at the SDL JNI boundary. */
public final class StencilReferencePass {
    private StencilReferencePass() {}

    public static byte[] adapt(String name, byte[] bytes) {
        if (!name.startsWith("com.gtnewhorizons.angelica.sdlgpu.")) return bytes;
        var node = new ClassNode();
        new ClassReader(bytes).accept(node, 0);
        boolean changed = false;
        for (var method : node.methods)
            for (var instruction : method.instructions) {
                if (instruction instanceof MethodInsnNode call
                        && call.owner.equals("org/lwjgl/sdl/SDLGPU")
                        && call.name.equals("SDL_SetGPUStencilReference")
                        && call.desc.equals("(JB)V")) {
                    call.owner = Endpoint.STENCIL_REFERENCE.owner();
                    call.name = "set";
                    changed = true;
                }
            }
        if (!changed) return bytes;
        var writer = new ClassWriter(0);
        node.accept(writer);
        return writer.toByteArray();
    }
}
