package dev.julia.probe.adapter;

/** Validated texture subregion and bounded upload tiling. */
record ClearRegion(int level, int x, int y, int z, int width, int height, int depth) {
    void validate(int levels, int mipWidth, int mipHeight, int mipDepth) {
        if (level < 0 || level >= levels || x < 0 || y < 0 || z < 0 || width < 0 || height < 0 || depth < 0
                || (long) x + width > mipWidth || (long) y + height > mipHeight || (long) z + depth > mipDepth) {
            throw new IllegalArgumentException("Texture clear region outside allocated storage");
        }
    }
    boolean empty() { return width == 0 || height == 0 || depth == 0; }
    int tileRows() {
        int rowBytes = Math.multiplyExact(width, 4);
        if (rowBytes <= 0 || rowBytes > 64 * 1024 * 1024) throw new UnsupportedOperationException("Texture clear row is too large");
        return Math.min(height, Math.max(1, 65536 / rowBytes));
    }
    static byte[] color(float[] values, boolean bgra) {
        if (values == null) return new byte[4];
        if (values.length < 4) throw new IllegalArgumentException("RGBA clear requires four components");
        byte[] result = new byte[4];
        for (int i = 0; i < 4; i++) {
            if (!Float.isFinite(values[i])) throw new UnsupportedOperationException("Non-finite clear component");
            result[i] = (byte) Math.round(Math.clamp(values[i], 0.0f, 1.0f) * 255.0f);
        }
        if (bgra) { byte red = result[0]; result[0] = result[2]; result[2] = red; }
        return result;
    }
}
