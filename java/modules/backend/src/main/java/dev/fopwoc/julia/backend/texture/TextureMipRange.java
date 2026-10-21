package dev.fopwoc.julia.backend.texture;

/** Requested texture levels remain queryable; immutable storage clamps only the sampling range. */
record TextureMipRange(int base, int max) {
    static final TextureMipRange DEFAULT = new TextureMipRange(0, 1000);

    TextureMipRange {
        if (base < 0 || max < 0) throw new IllegalArgumentException("Negative texture mip level");
    }

    TextureMipRange effective(int levels, boolean immutable) {
        if (levels <= 0) throw new IllegalStateException("Texture has no allocated mip levels");
        int first = immutable ? Math.min(base, levels - 1) : base;
        int last = immutable ? Math.clamp(max, first, levels - 1) : Math.min(max, levels - 1);
        if (first > last) throw new IllegalStateException("Incomplete texture mip range: " + base + ".." + max);
        return new TextureMipRange(first, last);
    }

    int levels() {
        return max - base + 1;
    }
}
