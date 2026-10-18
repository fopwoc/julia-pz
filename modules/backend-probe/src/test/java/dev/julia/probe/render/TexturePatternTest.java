package dev.julia.probe.render;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TexturePatternTest {
    @Test
    void nearestSamplingUsesPixelCentersForOddRectangularTargets() {
        int[][] expected = {
            {0xFF070D13, 0xFF120D50, 0xFF1D0D8D, 0xFF1D0D8D, 0xFF280DCA},
            {0xFF299313, 0xFF349350, 0xFF3F938D, 0xFF3F938D, 0xFF4A93CA},
            {0xFF3AD613, 0xFF45D650, 0xFF50D68D, 0xFF50D68D, 0xFF5BD6CA}
        };
        for (int y = 0; y < 3; y++) {
            for (int x = 0; x < 5; x++) assertEquals(expected[y][x], TexturePattern.expectedArgb(x, y, 5, 3), x + "," + y);
        }
    }

    @Test
    void permitsOnlyAdjacentTexelsAtExactInterpolationBoundaries() {
        assertTrue(TexturePattern.matchesArgb(0xFF070D13, 0, 545, 3600, 2182));
        assertTrue(TexturePattern.matchesArgb(0xFF185013, 0, 545, 3600, 2182));
        assertFalse(TexturePattern.matchesArgb(0xFF070D13, 0, 546, 3600, 2182));
        assertFalse(TexturePattern.matchesArgb(0xFF070D12, 0, 545, 3600, 2182));
        assertFalse(TexturePattern.matchesArgb(0xFF299313, 0, 545, 3600, 2182));
    }

}
