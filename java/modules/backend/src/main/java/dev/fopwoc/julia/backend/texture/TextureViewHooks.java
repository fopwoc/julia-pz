package dev.fopwoc.julia.backend.texture;

import com.gtnewhorizons.angelica.glsm.backend.BackendManager;
import com.gtnewhorizons.angelica.sdlgpu.frame.ContextState;
import com.gtnewhorizons.angelica.sdlgpu.resource.ResourceManager;
import dev.fopwoc.julia.backend.BackendInternals;

/** Version-pinned internal hooks; all arguments already belong to the isolated runtime. */
public final class TextureViewHooks {
    private TextureViewHooks() {}

    public static void delete(int name) {
        BackendInternals.context();
        BackendManager.RENDER_BACKEND.deleteTextures(name);
        BackendInternals.forgetTextureName(name);
    }

    public static boolean referenced(ResourceManager resources, long handle) {
        return BackendInternals.textureReferenced(resources, handle);
    }

    public static void beforeAllocation(int name) {
        BackendInternals.views().beforeAllocation(name);
    }

    public static void prepare(ContextState state) {
        BackendInternals.views().prepare(state);
    }

    public static long samplingHandle(ResourceManager resources, int name) {
        return BackendInternals.views().samplingHandle(name);
    }
}
