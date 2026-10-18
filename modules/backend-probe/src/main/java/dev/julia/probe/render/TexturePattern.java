package dev.julia.probe.render;

/** Asymmetric sixteen-color test texture and the expected BGRA shader result. */
public final class TexturePattern {
    public static final int EDGE = 4;

    private TexturePattern() { }

    public static int sourceArgb(int x, int y) {
        return 0xFF000000 | (19 + x * 61) << 16 | (13 + y * 67) << 8 | (7 + x * 11 + y * 17);
    }

    public static int expectedArgb(int x, int y, int size) {
        return expectedArgb(x, y, size, size);
    }

    public static int expectedArgb(int x, int y, int width, int height) {
        int source = sourceArgb((int) ((2L * x + 1) * EDGE / (2L * width)),
                (int) ((2L * y + 1) * EDGE / (2L * height)));
        return (source & 0xFF00FF00) | (source & 255) << 16 | (source >>> 16 & 255);
    }
}
