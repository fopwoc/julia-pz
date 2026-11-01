package dev.fopwoc.julia.backend.texture;

import com.gtnewhorizons.angelica.sdlgpu.Subsystems;
import com.gtnewhorizons.angelica.sdlgpu.Upstream;

/** Pinned GLSM integration keeps its operation cache aligned without changing any logical binding. */
public final class TextureBindingHooks {
    private TextureBindingHooks() {}

    public static void samplerOverride(long[] overrides) {
        Subsystems.current().textureBindings().samplerOverride(Upstream.context(), overrides != null);
    }
}
