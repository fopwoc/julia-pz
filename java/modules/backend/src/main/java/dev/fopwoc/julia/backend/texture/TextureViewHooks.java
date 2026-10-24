package dev.fopwoc.julia.backend.texture;

import com.gtnewhorizons.angelica.glsm.backend.BackendManager;
import com.gtnewhorizons.angelica.sdlgpu.frame.ContextState;
import com.gtnewhorizons.angelica.sdlgpu.resource.ResourceManager;
import dev.fopwoc.julia.backend.Subsystems;
import dev.fopwoc.julia.backend.Upstream;

/** Version-pinned internal hooks; all arguments already belong to the isolated runtime. */
public final class TextureViewHooks {
    private TextureViewHooks() {}

    public static void delete(int name) {
        Upstream.context();
        BackendManager.RENDER_BACKEND.deleteTextures(name);
        Upstream.forgetTextureName(name);
    }

    public static boolean referenced(ResourceManager resources, long handle) {
        return Upstream.textureReferenced(resources, handle);
    }

    public static void sharedAccess(long handle) {
        Subsystems.current().textureViews().sharedAccess(handle);
    }

    public static void beforeAllocation(int name) {
        Subsystems.current().textureViews().beforeAllocation(name);
    }

    public static void prepare(ContextState state) {
        var subsystems = Subsystems.current();
        subsystems.bufferTextures().prepare(state);
        subsystems.textureViews().prepare(state);
    }

    public static long samplingHandle(ResourceManager resources, int name) {
        var subsystems = Subsystems.current();
        var buffers = subsystems.bufferTextures();
        return buffers.contains(name)
                ? buffers.samplingHandle(name)
                : subsystems.textureViews().samplingHandle(resources, name);
    }
}
