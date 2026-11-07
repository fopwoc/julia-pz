package dev.fopwoc.julia.profiling.agent;

import java.util.List;
import java.util.function.Supplier;

/** The panel's memory and CPU rows, recomputed once a second because they're read on every UI pass. */
final class LiveResources implements Supplier<List<Row>> {
    private static final long SECOND = 1_000_000_000L;
    private long lastNanos;
    private long lastCpu;
    private List<Row> rows = List.of();

    @Override
    public List<Row> get() {
        long now = System.nanoTime();
        if (lastNanos != 0 && now - lastNanos < SECOND) return rows;
        rows = RunMetrics.live(new long[] {lastNanos, lastCpu}, now);
        lastNanos = now;
        lastCpu = RunMetrics.processCpu();
        return rows;
    }
}
