package dev.julia.probe.device;

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

    public static boolean matchesArgb(int actual, int x, int y, int width, int height) {
        long scaledX = (2L * x + 1) * EDGE;
        long scaledY = (2L * y + 1) * EDGE;
        int sx = (int) (scaledX / (2L * width));
        int sy = (int) (scaledY / (2L * height));
        int firstX = sx > 0 && scaledX % (2L * width) == 0 ? sx - 1 : sx;
        int firstY = sy > 0 && scaledY % (2L * height) == 0 ? sy - 1 : sy;
        for (int ty = firstY; ty <= sy; ty++) {
            for (int tx = firstX; tx <= sx; tx++) {
                int source = sourceArgb(tx, ty);
                int expected = (source & 0xFF00FF00) | (source & 255) << 16 | (source >>> 16 & 255);
                if (actual == expected) return true;
            }
        }
        return false;
    }

}
