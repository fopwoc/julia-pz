package dev.fopwoc.julia.backend.texture;

import static org.lwjgl.opengl.GL11.GL_TEXTURE_2D;
import static org.lwjgl.opengl.GL12.GL_TEXTURE_BASE_LEVEL;
import static org.lwjgl.opengl.GL12.GL_TEXTURE_MAX_LEVEL;
import static org.lwjgl.opengl.GL30.GL_TEXTURE_2D_ARRAY;
import static org.lwjgl.sdl.SDLGPU.*;

import com.gtnewhorizons.angelica.sdlgpu.SDLGPUGate;
import com.gtnewhorizons.angelica.sdlgpu.resource.ResourceManager;
import dev.fopwoc.julia.backend.Upstream;
import dev.fopwoc.julia.backend.sdl.SdlTextureViews;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.ints.IntSet;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import java.util.HashMap;
import java.util.Map;

/** Native sampling views implement base-relative fetches and restrict LOD without changing FBO mip addressing. */
final class TextureMipSampling implements AutoCloseable {
    // Read for every sampled texture on every draw, so names stay unboxed.
    private final Int2ObjectOpenHashMap<TextureMipRange> ranges = new Int2ObjectOpenHashMap<>();
    private final Long2ObjectOpenHashMap<Map<TextureMipRange, Long>> views = new Long2ObjectOpenHashMap<>();

    boolean parameter(int name, int parameter, int value) {
        if (parameter != GL_TEXTURE_BASE_LEVEL && parameter != GL_TEXTURE_MAX_LEVEL) return false;
        var old = ranges.getOrDefault(name, TextureMipRange.DEFAULT);
        var next = parameter == GL_TEXTURE_BASE_LEVEL
                ? new TextureMipRange(value, old.max())
                : new TextureMipRange(old.base(), value);
        if (!old.equals(next)) {
            // Queued draws must consume the previous image range before a texture parameter changes.
            Upstream.flushTextureReferences();
            ranges.put(name, next);
            Upstream.textureMipParameter(name, parameter, value);
            Upstream.context().samplerBindGen++;
        }
        return true;
    }

    Integer parameter(int name, int parameter) {
        var range = ranges.getOrDefault(name, TextureMipRange.DEFAULT);
        return switch (parameter) {
            case GL_TEXTURE_BASE_LEVEL -> range.base();
            case GL_TEXTURE_MAX_LEVEL -> range.max();
            default -> null;
        };
    }

    /** Runs for every sampled texture on every draw; the full-range default needs no texture metadata. */
    long handle(ResourceManager resources, int name, long source, IntSet immutable) {
        if (source == 0) return source;
        var requested = ranges.get(name);
        if (requested == null || requested.base() == 0 && requested.max() >= TextureMipRange.DEFAULT.max())
            return source;
        var meta = resources.getTextureMeta(name);
        if (meta == null || requested.base() == 0 && requested.max() >= meta.levels() - 1) return source;
        var range = requested.effective(meta.levels(), immutable.contains(name));
        if (range.base() == 0 && range.levels() == meta.levels()) return source;
        if (meta.glTarget() != GL_TEXTURE_2D && meta.glTarget() != GL_TEXTURE_2D_ARRAY)
            throw new UnsupportedOperationException("Sampling a restricted mip range requires a 2D or array texture");
        return views.computeIfAbsent(source, ignored -> new HashMap<>())
                .computeIfAbsent(
                        range,
                        selected -> SdlTextureViews.create(
                                source,
                                meta.glTarget() == GL_TEXTURE_2D
                                        ? SDL_GPU_TEXTURETYPE_2D
                                        : SDL_GPU_TEXTURETYPE_2D_ARRAY,
                                selected.base(),
                                selected.levels(),
                                0,
                                meta.depth()));
    }

    void deleted(int name) {
        ranges.remove(name);
    }

    void release(long source) {
        var cached = views.remove(source);
        if (cached == null) return;
        long device = SDLGPUGate.device().getDevice();
        for (long handle : cached.values()) SDL_ReleaseGPUTexture(device, handle);
    }

    boolean hasViews() {
        return !views.isEmpty();
    }

    @Override
    public void close() {
        for (long source : views.keySet().toLongArray()) release(source);
        ranges.clear();
    }
}
