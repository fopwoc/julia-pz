package dev.fopwoc.julia.backend.texture;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class TextureMipRangeTest {
    @Test
    void clampsImmutableSamplingWithoutChangingRequestedState() {
        var requested = new TextureMipRange(20, 2);
        assertEquals(new TextureMipRange(4, 4), requested.effective(5, true));
        assertEquals(20, requested.base());
        assertEquals(2, requested.max());
        assertEquals(new TextureMipRange(1, 4), new TextureMipRange(1, 1000).effective(5, true));
        assertEquals(new TextureMipRange(2, 2), new TextureMipRange(2, 0).effective(5, true));
    }

    @Test
    void rejectsIncompleteMutableSamplingAndNegativeParameters() {
        assertThrows(IllegalArgumentException.class, () -> new TextureMipRange(-1, 0));
        assertThrows(IllegalArgumentException.class, () -> new TextureMipRange(0, -1));
        assertThrows(IllegalStateException.class, () -> new TextureMipRange(5, 1000).effective(5, false));
        assertThrows(IllegalStateException.class, () -> new TextureMipRange(2, 1).effective(5, false));
        assertThrows(IllegalStateException.class, () -> TextureMipRange.DEFAULT.effective(0, true));
        assertEquals(new TextureMipRange(1, 3), new TextureMipRange(1, 3).effective(5, false));
    }
}
