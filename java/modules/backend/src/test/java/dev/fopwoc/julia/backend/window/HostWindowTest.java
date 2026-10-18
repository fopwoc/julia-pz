package dev.fopwoc.julia.backend.window;

import static org.junit.jupiter.api.Assertions.*;

import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class HostWindowTest {
    @Test
    void bindsOneBorrowedWindowAndReleasesItWithoutDestroyingAnything() {
        try (var binding = HostWindow.bind(42, 640, 480)) {
            assertEquals(42, binding.handle());
            assertThrows(IllegalStateException.class, () -> HostWindow.bind(43, 800, 600));
            assertEquals(42, HostWindow.getWindow());
        }
        assertEquals(0, HostWindow.getWindow());
        assertThrows(IllegalStateException.class, HostWindow::getWidth);
    }

    @Test
    void tracksRectangularResizeWithoutLosingOwnership() {
        try (var binding = HostWindow.bind(42, 640, 480)) {
            assertTrue(HostWindow.wasResized());
            binding.dimensions(640, 480);
            assertFalse(HostWindow.wasResized());
            binding.dimensions(801, 601);
            assertTrue(HostWindow.wasResized());
            assertEquals(801, HostWindow.getWidth());
            assertEquals(601, HostWindow.getHeight());
            assertEquals(42, binding.handle());
        }
    }

    @Test
    void rejectsWindowMutationsFromAnotherThread() throws InterruptedException {
        try (var binding = HostWindow.bind(42, 640, 480)) {
            var failure = new AtomicReference<Throwable>();
            var thread = Thread.ofPlatform().start(() -> {
                try {
                    binding.dimensions(800, 600);
                } catch (Throwable exception) {
                    failure.set(exception);
                }
            });
            thread.join();
            assertInstanceOf(IllegalStateException.class, failure.get());
            assertEquals(640, HostWindow.getWidth());
            assertEquals(42, binding.handle());
        }
    }

    @Test
    void logicalRenderingKeepsHostCoordinatesAndLeavesNativeProbeResolutionUnchanged() {
        long pixels = ((long) 1280 << 32) | 960;
        try (var binding = HostWindow.bind(42, 640, 480)) {
            assertEquals(pixels, HostWindow.renderSize(pixels));
            binding.useLogicalResolution();
            assertEquals(((long) 640 << 32) | 480, HostWindow.renderSize(pixels));
            binding.dimensions(801, 601);
            assertEquals(((long) 801 << 32) | 601, HostWindow.renderSize(pixels));
            assertEquals(0, HostWindow.renderSize(0));
        }
    }

    @Test
    void rejectsNullHandlesAndInvalidDimensions() {
        assertThrows(IllegalArgumentException.class, () -> HostWindow.bind(0, 640, 480));
        assertThrows(IllegalArgumentException.class, () -> HostWindow.bind(42, 0, 480));
        assertEquals(0, HostWindow.getWindow());
    }
}
