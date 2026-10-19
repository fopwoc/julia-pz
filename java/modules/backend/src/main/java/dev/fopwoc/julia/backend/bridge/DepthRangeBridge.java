package dev.fopwoc.julia.backend.bridge;

import com.gtnewhorizons.angelica.glsm.GLStateManager;

/** GL clamps each endpoint independently before caching state or forwarding it to the native viewport. */
public final class DepthRangeBridge {
    private DepthRangeBridge() {}

    public static void glDepthRange(double near, double far) {
        GLStateManager.glDepthRange(Math.clamp(near, 0.0, 1.0), Math.clamp(far, 0.0, 1.0));
    }
}
