package dev.julia.probe.device;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class OffscreenProbeTest {
    // Independent golden RGBA values after the shader's BGRA swizzle.
    private static final int[][] COLORS = {
        {0xFF070D13, 0xFF120D50, 0xFF1D0D8D, 0xFF280DCA},
        {0xFF185013, 0xFF235050, 0xFF2E508D, 0xFF3950CA},
        {0xFF299313, 0xFF349350, 0xFF3F938D, 0xFF4A93CA},
        {0xFF3AD613, 0xFF45D650, 0xFF50D68D, 0xFF5BD6CA}
    };

    @Test
    void verifiesEveryPixelAgainstIndependentPattern() {
        assertDoesNotThrow(() -> OffscreenProbe.verify(image(false), true));
    }

    @Test
    void rejectsFlippedTextureAndSingleCorruptedPixel() {
        assertThrows(IllegalStateException.class, () -> OffscreenProbe.verify(image(true), true));
        byte[] pixels = image(false);
        pixels[(19 * 32 + 11) * 4 + 2] ^= 1;
        var error = assertThrows(IllegalStateException.class, () -> OffscreenProbe.verify(pixels, true));
        assertTrue(error.getMessage().startsWith("Pixel 619 "));
    }

    @Test
    void rejectsTruncatedReadback() {
        assertThrows(IllegalArgumentException.class, () -> OffscreenProbe.verify(new byte[0], true));
    }

    private static byte[] image(boolean flipped) {
        byte[] rgba = new byte[32 * 32 * 4];
        for (int y = 0; y < 32; y++) {
            for (int x = 0; x < 32; x++) {
                int color = COLORS[(flipped ? 31 - y : y) / 8][x / 8];
                int offset = (y * 32 + x) * 4;
                rgba[offset] = (byte) (color >>> 16);
                rgba[offset + 1] = (byte) (color >>> 8);
                rgba[offset + 2] = (byte) color;
                rgba[offset + 3] = (byte) (color >>> 24);
            }
        }
        return rgba;
    }
}
