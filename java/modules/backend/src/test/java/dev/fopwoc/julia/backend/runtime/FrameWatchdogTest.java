package dev.fopwoc.julia.backend.runtime;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FrameWatchdogTest {
    @Test
    void reportsEveryThreadOnStallAndNotesResumption(@TempDir Path output) throws Exception {
        var report = output.resolve("hang-1.txt");
        try (var watchdog = new FrameWatchdog(output, Duration.ofMillis(200), Duration.ofMillis(20))) {
            watchdog.presented(42);
            awaitText(report, "after frame 42");
            String stall = Files.readString(report);
            assertTrue(stall.contains("\"" + Thread.currentThread().getName() + "\""), stall);
            assertTrue(stall.contains("reportsEveryThreadOnStallAndNotesResumption"), "stacks must not be truncated");

            watchdog.presented(43);
            awaitText(report, "resumed after");
            assertFalse(Files.exists(output.resolve("hang-2.txt")), "one stall must produce one report");
        }
    }

    @Test
    void keepsSamplingRunnableThreadsWhileTheStallLasts(@TempDir Path output) throws Exception {
        var report = output.resolve("hang-1.txt");
        var spinning = new AtomicBoolean(true);
        var spinner = Thread.ofPlatform().name("julia-test-spinner").start(() -> {
            while (spinning.get()) Thread.onSpinWait();
        });
        try (var watchdog = new FrameWatchdog(output, Duration.ofMillis(200), Duration.ofMillis(20))) {
            watchdog.presented(7);
            awaitText(report, "sample 2 after");
            String samples = Files.readString(report);
            String second = samples.substring(samples.indexOf("sample 2 after"));
            assertTrue(second.contains("\"julia-test-spinner\""), second);
            assertFalse(second.contains("Thread.sleep"), "samples list only runnable threads: " + second);
        } finally {
            spinning.set(false);
            spinner.join();
        }
    }

    private static void awaitText(Path file, String text) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (System.nanoTime() < deadline) {
            if (Files.exists(file) && Files.readString(file).contains(text)) return;
            Thread.sleep(20);
        }
        fail("Timed out waiting for '" + text + "' in " + file);
    }
}
