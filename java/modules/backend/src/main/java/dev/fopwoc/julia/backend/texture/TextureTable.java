package dev.fopwoc.julia.backend.texture;

import dev.fopwoc.julia.backend.texture.buffer.BufferTextureView;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import java.util.Arrays;

/**
 * Julia's state for every GL texture name, one record per name. Sampling reads several of these facts for every bound
 * texture on every draw, so GL's small, dense names index an array; unusually large names fall back to a map.
 *
 * <p>A record lives from the first time Julia learns of a name until {@link #deleted} drops it, so a recycled name
 * starts clean.
 */
public final class TextureTable {
    /** Julia's facts about one texture name; upstream keeps storage, handles, and sampler state. */
    public static final class Texture {
        // 0 until the name is first bound or created; GL fixes it from then on.
        int target;
        // Generated but never bound, so it may still become a texture view.
        boolean fresh;
        boolean immutable;
        boolean view;
        // Samples the stencil aspect; every other texture samples depth, the GL default.
        boolean stencil;
        // null means the GL default base and maximum levels.
        TextureMipRange mip;
        TextureViewRange range;
        // Last sampling handle and the storage generation it was resolved at; see TextureViews.samplingHandle.
        long sampling, samplingStamp;
        private BufferTextureView buffer;

        private Texture() {}

        public int target() {
            return target;
        }

        /** The buffer-texture attachment, or {@code null} for every texture not bound to {@code GL_TEXTURE_BUFFER}. */
        public BufferTextureView buffer() {
            return buffer;
        }

        public void buffer(BufferTextureView buffer) {
            this.buffer = buffer;
        }
    }

    private static final int DENSE_LIMIT = 1 << 20;

    private Texture[] dense = new Texture[1024];
    private final Int2ObjectOpenHashMap<Texture> sparse = new Int2ObjectOpenHashMap<>();

    /** The record for {@code name}, or {@code null} when Julia holds no state for it. */
    public Texture get(int name) {
        if (name >= 0 && name < dense.length) return dense[name];
        return name < 0 ? null : sparse.get(name);
    }

    public Texture getOrCreate(int name) {
        if (name < 0) throw new IllegalArgumentException("Negative texture name: " + name);
        var texture = get(name);
        if (texture != null) return texture;
        texture = new Texture();
        if (name < DENSE_LIMIT) {
            if (name >= dense.length)
                dense = Arrays.copyOf(dense, Math.min(DENSE_LIMIT, Math.max(name + 1, dense.length * 2)));
            dense[name] = texture;
        } else sparse.put(name, texture);
        return texture;
    }

    /** Called last when a name is deleted, after every subsystem has read its final state. */
    public void deleted(int name) {
        if (name < 0) return;
        if (name < dense.length) dense[name] = null;
        else sparse.remove(name);
    }

    public void clear() {
        Arrays.fill(dense, null);
        sparse.clear();
    }
}
