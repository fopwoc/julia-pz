package dev.fopwoc.julia.profiling.core;

import java.util.Arrays;

/** Nearest-rank frame-time percentiles; average FPS derives from total measured time. */
public record FrameStatistics(int frames, double averageFps, long medianNs, long p95Ns, long p99Ns, long maxNs) {
    public static FrameStatistics of(long[] intervals) {
        if (intervals.length == 0) return new FrameStatistics(0, 0, 0, 0, 0, 0);
        long[] sorted = intervals.clone();
        long total = 0;
        for (long interval : sorted) {
            if (interval <= 0) throw new IllegalArgumentException("Positive intervals required");
            total = Math.addExact(total, interval);
        }
        Arrays.sort(sorted);
        return new FrameStatistics(
                sorted.length,
                1_000_000_000.0 * sorted.length / total,
                rank(sorted, .5),
                rank(sorted, .95),
                rank(sorted, .99),
                sorted[sorted.length - 1]);
    }

    private static long rank(long[] sorted, double percentile) {
        return sorted[(int) Math.ceil(sorted.length * percentile) - 1];
    }
}
