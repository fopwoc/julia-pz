package dev.fopwoc.julia.backend.bridge;

import dev.fopwoc.julia.backend.Subsystems;

public final class TextureViewBridge {
    private TextureViewBridge() {}

    public static void glTextureView(
            int texture, int target, int original, int format, int minLevel, int levels, int minLayer, int layers) {
        Subsystems.current()
                .textureViews()
                .create(texture, target, original, format, minLevel, levels, minLayer, layers);
    }
}
