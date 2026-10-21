package dev.fopwoc.julia.backend.diagnostic;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DrawTraceTest {
    @TempDir
    Path output;

    @Test
    void requestRestartsCaptureAtTheNextFrameBoundary() throws Exception {
        try (var trace = new DrawTrace(true)) {
            trace.open(output);
            Path log = output.resolve("draw-trace.txt");
            String initial = Files.readString(log);
            assertTrue(initial.contains("captureFrame=0"));
            trace.frameBoundary();
            assertEquals(initial, Files.readString(log));
            Files.createFile(output.resolve("draw-trace.request"));
            trace.frameBoundary();
            assertFalse(Files.exists(output.resolve("draw-trace.request")));
            assertTrue(Files.readString(log).contains("captureFrame=2"));
        }
    }

    @Test
    void disabledTraceDoesNotConsumeRequests() throws Exception {
        Path request = Files.createFile(output.resolve("draw-trace.request"));
        try (var trace = new DrawTrace(false)) {
            trace.open(output);
            trace.frameBoundary();
        }
        assertTrue(Files.exists(request));
        assertFalse(Files.exists(output.resolve("draw-trace.txt")));
    }
}
