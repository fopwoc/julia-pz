package dev.fopwoc.julia.profiling.agent;

import java.util.List;
import java.util.Locale;
import java.util.function.Supplier;

/** The panel's memory and CPU rows, recomputed once a second because they're read on every UI pass. */
final class LiveResources implements Supplier<List<Row>> {
    private static final long SECOND = 1_000_000_000L;
    private long lastNanos;
    private long lastProcess;
    private long lastRender;
    private long lastLogic;
    private List<Row> rows = List.of();

    @Override
    public List<Row> get() {
        long now = System.nanoTime();
        if (lastNanos != 0 && now - lastNanos < SECOND) return rows;
        long process = RunMetrics.processCpu();
        long render = RunMetrics.threadCpu(ProfilingHooks.renderThread());
        long logic = RunMetrics.threadCpu(ProfilingHooks.logicThread());
        long elapsed = Math.max(1, now - lastNanos);
        boolean first = lastNanos == 0;
        rows = List.of(
                new Row(
                        "Heap",
                        RunMetrics.heapUsed() / RunMetrics.MEGABYTE + " / "
                                + Runtime.getRuntime().maxMemory() / RunMetrics.MEGABYTE + " MB"),
                new Row("CPU", first ? "-" : percent(process - lastProcess, elapsed) + " of a core"),
                new Row(
                        "Threads",
                        first
                                ? "-"
                                : String.format(
                                        Locale.ROOT,
                                        "render %s, logic %s",
                                        percent(render - lastRender, elapsed),
                                        percent(logic - lastLogic, elapsed))));
        lastNanos = now;
        lastProcess = process;
        lastRender = render;
        lastLogic = logic;
        return rows;
    }

    private static String percent(long cpu, long elapsed) {
        return Math.round(cpu * 100.0 / elapsed) + "%";
    }
}
