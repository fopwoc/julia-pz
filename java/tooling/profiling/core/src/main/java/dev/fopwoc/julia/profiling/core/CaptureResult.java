package dev.fopwoc.julia.profiling.core;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/** Immutable completed recording. Writing and percentile calculation happen outside the render hook. */
public final class CaptureResult {
    private final CapturePlan plan;
    private final CaptureSession.State state;
    private final long[] elapsed, intervals, steps;

    CaptureResult(CapturePlan plan, CaptureSession.State state, long[] elapsed, long[] intervals, long[] steps) {
        this.plan = plan;
        this.state = state;
        this.elapsed = elapsed;
        this.intervals = intervals;
        this.steps = steps;
    }

    public CaptureSession.State state() {
        return state;
    }

    public FrameStatistics statistics() {
        return FrameStatistics.of(intervals);
    }

    public void write(Path directory) throws IOException {
        Files.createDirectory(directory);
        try (var writer = Files.newBufferedWriter(directory.resolve("frames.csv"))) {
            writer.write("elapsed_ns,frame_interval_ns,render_step_wall_ns\n");
            for (int i = 0; i < intervals.length; i++)
                writer.write(elapsed[i] + "," + intervals[i] + "," + steps[i] + "\n");
        }
        var statistics = statistics();
        var summary = new Properties();
        summary.setProperty("scenario", plan.scenario());
        summary.setProperty("status", state.name());
        summary.setProperty("warmup_ms", Long.toString(plan.warmup().toMillis()));
        summary.setProperty("duration_ms", Long.toString(plan.duration().toMillis()));
        summary.setProperty("frames", Integer.toString(statistics.frames()));
        summary.setProperty("average_fps", Double.toString(statistics.averageFps()));
        summary.setProperty("median_ns", Long.toString(statistics.medianNs()));
        summary.setProperty("p95_ns", Long.toString(statistics.p95Ns()));
        summary.setProperty("p99_ns", Long.toString(statistics.p99Ns()));
        summary.setProperty("max_ns", Long.toString(statistics.maxNs()));
        summary.setProperty(
                "timing_scope",
                "completed render-step intervals; CPU wall duration includes waiting; no GPU timestamps");
        try (var writer = Files.newBufferedWriter(directory.resolve("summary.properties"))) {
            summary.store(writer, "Profiling result");
        }
    }
}
