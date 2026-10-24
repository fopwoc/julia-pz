package dev.fopwoc.julia.backend.framebuffer;

import com.gtnewhorizons.angelica.glsm.GLStateManager;
import com.gtnewhorizons.angelica.sdlgpu.pipeline.Hashing;
import dev.fopwoc.julia.backend.Subsystems;

/** Pinned pipeline blend-op lookup and GLSM cache invalidation while per-output equations differ. */
public final class BlendEquationHooks {
    private BlendEquationHooks() {}

    public static int color(int upstream, int output) {
        return Subsystems.current().blendEquations().color(output, upstream);
    }

    public static int alpha(int upstream, int output) {
        return Subsystems.current().blendEquations().alpha(output, upstream);
    }

    public static long hash(long upstream) {
        var equations = Subsystems.current().blendEquations();
        return equations.indexed() ? Hashing.fmix64(upstream, equations.packed()) : upstream;
    }

    /** GLSM skips a global equation equal to its cache, which would leave differing outputs in place. */
    public static boolean cacheEnabled() {
        return GLStateManager.isCachingEnabled()
                && !Subsystems.current().blendEquations().indexed();
    }
}
