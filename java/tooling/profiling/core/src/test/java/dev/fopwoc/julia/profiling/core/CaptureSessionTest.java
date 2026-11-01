package dev.fopwoc.julia.profiling.core;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CaptureSessionTest {
    private static CapturePlan plan(long warmup, long duration) {
        return new CapturePlan("forest", Duration.ofNanos(warmup), Duration.ofNanos(duration));
    }

    @Test
    void discardsWarmupAndPrimingIntervalAndKeepsOnlyCompletedWindowSamples(@TempDir Path output) throws Exception {
        var session = new CaptureSession(plan(100, 100), 8);
        session.frame(1000, 2);
        session.frame(1090, 3);
        session.frame(1100, 4);
        session.frame(1110, 5);
        session.frame(1140, 6);
        session.frame(1190, 7);
        session.frame(1200, 8);
        var result = session.result();
        assertEquals(CaptureSession.State.COMPLETE, result.state());
        assertEquals(3, result.statistics().frames());
        assertEquals(30, result.statistics().medianNs());
        assertEquals(50, result.statistics().p99Ns());
        result.write(output.resolve("capture"));
        assertEquals(
                "elapsed_ns,frame_interval_ns,render_step_wall_ns\n10,10,5\n40,30,6\n90,50,7\n",
                Files.readString(output.resolve("capture/frames.csv")));
    }

    @Test
    void distinguishesInterruptedAndCapacityLimitedRecordings() {
        var stopped = new CaptureSession(plan(0, 100), 2);
        stopped.stop();
        assertEquals(CaptureSession.State.STOPPED, stopped.result().state());
        var bounded = new CaptureSession(plan(0, 100), 1);
        bounded.frame(1000, 0);
        bounded.frame(1010, 1);
        bounded.frame(1020, 1);
        assertEquals(CaptureSession.State.OVERFLOW, bounded.result().state());
        assertEquals(1, bounded.result().statistics().frames());
    }

    @Test
    void rejectsBackwardsTimeAndPrematureResults() {
        var session = new CaptureSession(plan(0, 100), 2);
        session.frame(1000, 0);
        assertThrows(IllegalArgumentException.class, () -> session.frame(999, 0));
        assertThrows(IllegalStateException.class, session::result);
    }

    @Test
    void percentilesUseFrameTimesAndFpsUsesTotalTime() {
        var values = FrameStatistics.of(new long[] {10_000_000, 30_000_000, 10_000_000, 50_000_000});
        assertEquals(40, values.averageFps());
        assertEquals(10_000_000, values.medianNs());
        assertEquals(50_000_000, values.p95Ns());
    }
}
