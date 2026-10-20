package dev.fopwoc.julia.transform.load;

import dev.fopwoc.julia.transform.Endpoint;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;

/** Zero-extends unsigned stencil arguments at the SDL JNI boundary. */
public final class StencilReferencePass implements UpstreamPatch {

    @Override
    public boolean targets(String name) {
        return name.startsWith("com.gtnewhorizons.angelica.sdlgpu.");
    }

    @Override
    public boolean apply(String name, ClassNode node) {
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
        return changed;
    }
}
