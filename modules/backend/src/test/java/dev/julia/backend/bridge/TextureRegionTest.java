package dev.julia.backend.bridge;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TextureRegionTest {
    @Test void validatesRegionsWithoutIntegerOverflow() {
        new TextureRegion(0, 2, 1, 1, 3, 2, 1).validate(1, 8, 4, 2);
        assertThrows(IllegalArgumentException.class, () -> new TextureRegion(0, Integer.MAX_VALUE, 0, 0, 3, 1, 1).validate(1, 8, 4, 2));
        assertThrows(IllegalArgumentException.class, () -> new TextureRegion(1, 0, 0, 0, 1, 1, 1).validate(1, 8, 4, 2));
        assertThrows(IllegalArgumentException.class, () -> new TextureRegion(0, 0, 0, 2, 1, 1, 1).validate(1, 8, 4, 2));
        assertTrue(new TextureRegion(0, 0, 0, 0, 0, 1, 1).empty());
    }

    @Test void boundsTilesAndEncodesColorAccordingToStorageOrder() {
        assertEquals(256, new TextureRegion(0, 0, 0, 0, 64, 260, 2).tileRows());
        assertArrayEquals(new byte[] {(byte) 153, 102, 51, (byte) 255}, TextureRegion.color(new float[] {0.2f, 0.4f, 0.6f, 1}, true));
        assertArrayEquals(new byte[] {0, (byte) 255, 0, (byte) 255}, TextureRegion.color(new float[] {-1, 2, 0, 1}, false));
        assertArrayEquals(new byte[4], TextureRegion.color(null, false));
        assertThrows(UnsupportedOperationException.class, () -> TextureRegion.color(new float[] {Float.NaN, 0, 0, 1}, false));
        assertThrows(IllegalArgumentException.class, () -> TextureRegion.color(new float[] {1}, false));
    }
}
