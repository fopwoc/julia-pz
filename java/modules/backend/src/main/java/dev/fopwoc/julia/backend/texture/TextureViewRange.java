package dev.fopwoc.julia.backend.texture;

import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL30.*;

import com.gtnewhorizons.angelica.sdlgpu.resource.ResourceManager.TextureMeta;

/** Effective view ranges are relative to storage; creation arguments are relative to their source view. */
public record TextureViewRange(int minLevel, int levels, int minLayer, int layers, int immutableLevels) {
    public static TextureViewRange storage(TextureMeta meta) {
        return new TextureViewRange(0, meta.levels(), 0, meta.depth(), meta.levels());
    }

    public TextureViewRange subset(
            TextureMeta meta, int target, int format, int level, int count, int layer, int slices) {
        if ((target != GL_TEXTURE_2D && target != GL_TEXTURE_2D_ARRAY)
                || (meta.glTarget() != GL_TEXTURE_2D && meta.glTarget() != GL_TEXTURE_2D_ARRAY)
                || format != meta.glFormat())
            throw new UnsupportedOperationException("Texture views require matching formats and 2D/array targets");
        if (level < 0
                || level >= levels
                || count <= 0
                || layer < 0
                || layer >= layers
                || slices <= 0
                || (target == GL_TEXTURE_2D && slices != 1))
            throw new IllegalArgumentException("Texture view range is outside source storage");
        return new TextureViewRange(
                Math.addExact(minLevel, level),
                Math.min(count, levels - level),
                Math.addExact(minLayer, layer),
                Math.min(slices, layers - layer),
                immutableLevels);
    }
}
