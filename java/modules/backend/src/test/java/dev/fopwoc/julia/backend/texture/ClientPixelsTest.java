package dev.fopwoc.julia.backend.texture;

import static org.junit.jupiter.api.Assertions.*;

import com.gtnewhorizons.angelica.sdlgpu.frame.ContextState.PixelStoreState;
import org.junit.jupiter.api.Test;

class ClientPixelsTest {
    @Test
    void computesLastReadableByteWithAlignmentAndSkips() {
        var store = new PixelStoreState();
        assertEquals(24, ClientPixels.requiredBytes(3, 2, 4, store));
        assertEquals(21, ClientPixels.requiredBytes(3, 2, 3, store));
        store.unpackRowLength = 5;
        store.unpackSkipRows = 1;
        store.unpackSkipPixels = 2;
        assertEquals(47, ClientPixels.requiredBytes(3, 2, 3, store));
        assertEquals(0, ClientPixels.requiredBytes(3, 0, 3, store));
    }

    @Test
    void rejectsUnaddressableSpansAndInvalidStateBeforeBorrowingMemory() {
        var store = new PixelStoreState();
        assertThrows(ArithmeticException.class, () -> ClientPixels.requiredBytes(Integer.MAX_VALUE, 2, 4, store));
        assertThrows(IllegalArgumentException.class, () -> ClientPixels.requiredBytes(-1, 2, 4, store));
        store.unpackAlignment = 3;
        assertThrows(IllegalArgumentException.class, () -> ClientPixels.requiredBytes(3, 2, 4, store));
    }
}
