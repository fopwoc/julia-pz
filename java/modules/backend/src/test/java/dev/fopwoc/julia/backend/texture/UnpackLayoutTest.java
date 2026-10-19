package dev.fopwoc.julia.backend.texture;

import static org.junit.jupiter.api.Assertions.*;

import com.gtnewhorizons.angelica.sdlgpu.frame.ContextState.PixelStoreState;
import org.junit.jupiter.api.Test;

class UnpackLayoutTest {
    @Test
    void finalRowDoesNotRequireTrailingPadding() {
        var layout = UnpackLayout.of(3, 3, 1, 1, new PixelStoreState(), 0, 0);
        assertEquals(4, layout.rowStride());
        assertEquals(11, layout.requiredBytes());
    }

    @Test
    void rowAndImageSkipsUseTheirOwnStrides() {
        var state = new PixelStoreState();
        state.unpackRowLength = 3;
        state.unpackSkipRows = 2;
        state.unpackSkipPixels = 1;
        var layout = UnpackLayout.of(3, 3, 2, 1, state, 5, 1);
        assertEquals(4, layout.rowStride());
        assertEquals(20, layout.imageStride());
        assertEquals(29, layout.start());
        assertEquals(60, layout.requiredBytes());
    }

    @Test
    void emptyUploadsNeedNoClientMemory() {
        assertEquals(0, UnpackLayout.of(0, 3, 7, 4, new PixelStoreState(), 0, 0).requiredBytes());
    }

    @Test
    void invalidOrOverflowingLayoutsFailBeforeNativeMemoryAccess() {
        var state = new PixelStoreState();
        state.unpackAlignment = 3;
        assertThrows(IllegalArgumentException.class, () -> UnpackLayout.of(3, 3, 1, 4, state, 0, 0));
        state.unpackAlignment = 4;
        assertThrows(
                ArithmeticException.class,
                () -> UnpackLayout.of(Integer.MAX_VALUE, Integer.MAX_VALUE, 4, 16, state, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> UnpackLayout.of(3, 3, 1, 4, state, -1, 0));
    }
}
