package dev.fopwoc.julia.transform.load;

import dev.fopwoc.julia.transform.Endpoint;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;

/** Replaces the backend's Minecraft resource-loader dependency with its own published shader assets. */
public final class ShaderSourcePass implements UpstreamPatch {

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
                        && call.owner.equals("org/embeddedt/embeddium/impl/render/shader/ShaderLoader")
                        && call.name.equals("getShaderSource")
                        && call.desc.equals("(Ljava/lang/String;)Ljava/lang/String;")) {
                    call.owner = Endpoint.SHADER_SOURCES.owner();
                    changed = true;
                }
            }
        return changed;
    }
}
