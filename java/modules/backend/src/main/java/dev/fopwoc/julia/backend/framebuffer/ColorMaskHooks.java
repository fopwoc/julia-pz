package dev.fopwoc.julia.backend.framebuffer;

import com.gtnewhorizons.angelica.glsm.GLStateManager;
import com.gtnewhorizons.angelica.sdlgpu.pipeline.Hashing;
import dev.fopwoc.julia.backend.Subsystems;

/** Pinned graphics-pipeline mask lookup and global GLSM cache invalidation after indexed changes. */
public final class ColorMaskHooks {
    private ColorMaskHooks() {}

    public static int mask(int output) {
        return Subsystems.current().colorMasks().mask(output);
    }

    public static long hash(long upstream) {
        return Hashing.fmix64(upstream, Subsystems.current().colorMasks().packed());
    }

    public static boolean cacheEnabled() {
        return GLStateManager.isCachingEnabled()
                && !Subsystems.current().colorMasks().indexed();
    }
}
