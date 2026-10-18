package dev.julia.probe.render;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;

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
}
