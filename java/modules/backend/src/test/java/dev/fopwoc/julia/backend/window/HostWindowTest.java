package dev.fopwoc.julia.backend.window;

import static org.junit.jupiter.api.Assertions.*;

import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class HostWindowTest {
    @Test
    void bindsOneBorrowedWindowAndReleasesItWithoutDestroyingAnything() {
        HostWindow released;
        try (var binding = HostWindow.bind(42, 640, 480)) {
            released = binding;
            assertEquals(42, binding.handle());
            assertThrows(IllegalStateException.class, () -> HostWindow.bind(43, 800, 600));
            assertEquals(42, binding.window());
        }
        assertEquals(0, released.window());
        assertThrows(IllegalStateException.class, released::width);
    }

    @Test
    void tracksRectangularResizeWithoutLosingOwnership() {
        try (var binding = HostWindow.bind(42, 640, 480)) {
            assertTrue(binding.wasResized());
            binding.dimensions(640, 480);
            assertFalse(binding.wasResized());
            binding.dimensions(801, 601);
            assertTrue(binding.wasResized());
            assertEquals(801, binding.width());
            assertEquals(601, binding.height());
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
            assertEquals(640, binding.width());
            assertEquals(42, binding.handle());
        }
    }

    @Test
    void logicalRenderingKeepsHostCoordinatesAndLeavesNativeProbeResolutionUnchanged() {
        long pixels = ((long) 1280 << 32) | 960;
        try (var binding = HostWindow.bind(42, 640, 480)) {
            assertEquals(pixels, binding.renderSize(pixels));
            binding.useLogicalResolution();
            assertEquals(((long) 640 << 32) | 480, binding.renderSize(pixels));
            binding.dimensions(801, 601);
            assertEquals(((long) 801 << 32) | 601, binding.renderSize(pixels));
            assertEquals(0, binding.renderSize(0));
        }
    }

    @Test
    void rejectsNullHandlesAndInvalidDimensions() {
        assertThrows(IllegalArgumentException.class, () -> HostWindow.bind(0, 640, 480));
        assertThrows(IllegalArgumentException.class, () -> HostWindow.bind(42, 0, 480));
        try (var binding = HostWindow.bind(42, 640, 480)) {
            assertEquals(42, binding.window());
        }
    }
}
