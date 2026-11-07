package dev.fopwoc.julia.profiling.agent;

import com.sun.management.OperatingSystemMXBean;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.util.Properties;
import java.util.concurrent.TimeUnit;

/**
 * Memory and CPU around a recording. These vary between runs and only show broad differences. CPU is percent of one core,
 * so a process using two cores fully reads 200. GPU memory isn't here: the renderer would have to report it.
 */
final class RunMetrics {
    private static final OperatingSystemMXBean SYSTEM =
            (OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();
    private static final java.lang.management.ThreadMXBean THREADS = ManagementFactory.getThreadMXBean();
    static final long MEGABYTE = 1024 * 1024;

    private long startNanos;
    private long processStart;
    private long renderStart;
    private long logicStart;
    private long heapPeak;

    void start() {
        startNanos = System.nanoTime();
        processStart = SYSTEM.getProcessCpuTime();
        renderStart = threadCpu(ProfilingHooks.renderThread());
        logicStart = threadCpu(ProfilingHooks.logicThread());
        heapPeak = 0;
        sample();
    }

    /** Runs on the control thread, a few times a second. */
    void sample() {
        heapPeak = Math.max(heapPeak, heapUsed());
    }

    void write(Properties into) {
        sample();
        long elapsed = Math.max(1, System.nanoTime() - startNanos);
        into.setProperty("heap_used_mb", Long.toString(heapUsed() / MEGABYTE));
        into.setProperty("heap_peak_mb", Long.toString(heapPeak / MEGABYTE));
        into.setProperty("heap_max_mb", Long.toString(Runtime.getRuntime().maxMemory() / MEGABYTE));
        long rss = residentMegabytes();
        if (rss >= 0) into.setProperty("rss_mb", Long.toString(rss));
        into.setProperty("cpu_process_pct", percent(SYSTEM.getProcessCpuTime() - processStart, elapsed));
        into.setProperty("cpu_render_pct", percent(threadCpu(ProfilingHooks.renderThread()) - renderStart, elapsed));
        into.setProperty("cpu_logic_pct", percent(threadCpu(ProfilingHooks.logicThread()) - logicStart, elapsed));
    }

    static long heapUsed() {
        var runtime = Runtime.getRuntime();
        return runtime.totalMemory() - runtime.freeMemory();
    }

    /** Whole process including native memory. The JVM has no call for it, and this also keeps the cost off frames. */
    static long residentMegabytes() {
        if (System.getProperty("os.name").startsWith("Windows")) return -1;
        try {
            var process = new ProcessBuilder(
                            "ps",
                            "-o",
                            "rss=",
                            "-p",
                            Long.toString(ProcessHandle.current().pid()))
                    .redirectErrorStream(true)
                    .start();
            var output = new String(process.getInputStream().readAllBytes()).strip();
            if (!process.waitFor(2, TimeUnit.SECONDS)) process.destroyForcibly();
            return Long.parseLong(output) / 1024;
        } catch (IOException | NumberFormatException failure) {
            return -1;
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            return -1;
        }
    }

    static long processCpu() {
        return SYSTEM.getProcessCpuTime();
    }

    static long threadCpu(long thread) {
        if (thread == 0) return 0;
        long time = THREADS.getThreadCpuTime(thread);
        return Math.max(0, time);
    }

    private static String percent(long cpu, long elapsed) {
        return Long.toString(Math.round(cpu * 100.0 / elapsed));
    }
}
