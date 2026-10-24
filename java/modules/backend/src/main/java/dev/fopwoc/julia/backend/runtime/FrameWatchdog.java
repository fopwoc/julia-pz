package dev.fopwoc.julia.backend.runtime;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.Instant;

/**
 * Records every thread's stack when the game stops presenting frames. A freeze raises no exception, and Steam on Linux
 * discards the JVM's own thread dumps, so this report is the only way a player can show where rendering is stuck. While
 * the stall lasts it keeps appending short samples of the running threads, which tell a loop from slow progress. It never
 * stops the game.
 */
final class FrameWatchdog implements AutoCloseable {
    private static final System.Logger LOG = System.getLogger(FrameWatchdog.class.getName());
    private static final int MAX_REPORTS = 3;
    private static final int MAX_SAMPLES = 8;
    private static final int SAMPLE_FRAMES = 12;

    private final Path output;
    private final Duration stall;
    private final Duration poll;
    private final Thread thread;
    private volatile long lastPresent = System.nanoTime();
    private volatile long frames;

    FrameWatchdog(Path output) {
        this(output, Duration.ofSeconds(15), Duration.ofSeconds(1));
    }

    FrameWatchdog(Path output, Duration stall, Duration poll) {
        this.output = output;
        this.stall = stall;
        this.poll = poll;
        thread = Thread.ofPlatform().daemon().name("Julia frame watchdog").start(this::watch);
    }

    void presented(long frames) {
        this.frames = frames;
        lastPresent = System.nanoTime();
    }

    private void watch() {
        int reports = 0, samples = 0;
        Path stalled = null;
        long stallStart = 0, lastSample = 0;
        try {
            while (true) {
                Thread.sleep(poll);
                long since = System.nanoTime() - lastPresent;
                if (since < stall.toNanos()) {
                    if (stalled != null) {
                        append(stalled, "\nresumed after " + Duration.ofNanos(System.nanoTime() - stallStart) + "\n");
                        stalled = null;
                    }
                } else if (stalled == null && reports < MAX_REPORTS) {
                    reports++;
                    samples = 0;
                    stallStart = lastPresent;
                    lastSample = System.nanoTime();
                    stalled = output.resolve("hang-" + reports + ".txt");
                    write(stalled, Duration.ofNanos(since));
                } else if (stalled != null
                        && samples < MAX_SAMPLES
                        && System.nanoTime() - lastSample >= stall.toNanos()) {
                    samples++;
                    lastSample = System.nanoTime();
                    sample(stalled, samples, Duration.ofNanos(since));
                }
            }
        } catch (InterruptedException closed) {
            Thread.currentThread().interrupt();
        }
    }

    private void write(Path report, Duration since) {
        var text = new StringBuilder("Julia frame stall\n")
                .append("time=")
                .append(Instant.now())
                .append("\nno present for ")
                .append(since.toSeconds())
                .append("s after frame ")
                .append(frames)
                .append("\n\n");
        // Full stacks with lock ownership; ThreadInfo.toString() would cut each stack at eight frames.
        for (ThreadInfo info : ManagementFactory.getThreadMXBean().dumpAllThreads(true, true)) {
            text.append('"').append(info.getThreadName()).append("\" ").append(info.getThreadState());
            if (info.getLockName() != null) text.append(" on ").append(info.getLockName());
            if (info.getLockOwnerName() != null)
                text.append(" owned by \"").append(info.getLockOwnerName()).append('"');
            text.append('\n');
            for (var frame : info.getStackTrace())
                text.append("\tat ").append(frame).append('\n');
            text.append('\n');
        }
        append(report, text.toString());
        LOG.log(
                System.Logger.Level.WARNING,
                "No frame presented for {0}s; thread report: {1}",
                since.toSeconds(),
                report);
    }

    /** Top frames of runnable threads only; a thread stuck in one loop shows the same frames sample after sample. */
    private static void sample(Path report, int number, Duration since) {
        var text = new StringBuilder("\nsample ")
                .append(number)
                .append(" after ")
                .append(since.toSeconds())
                .append("s, runnable threads:\n");
        for (ThreadInfo info : ManagementFactory.getThreadMXBean().dumpAllThreads(false, false)) {
            if (info.getThreadState() != Thread.State.RUNNABLE || info.getStackTrace().length == 0) continue;
            text.append('"').append(info.getThreadName()).append("\"\n");
            var stack = info.getStackTrace();
            for (int i = 0; i < Math.min(stack.length, SAMPLE_FRAMES); i++)
                text.append("\tat ").append(stack[i]).append('\n');
        }
        append(report, text.toString());
    }

    private static void append(Path report, String text) {
        try {
            Files.writeString(report, text, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException failure) {
            LOG.log(System.Logger.Level.ERROR, "Cannot write frame stall report " + report, failure);
        }
    }

    @Override
    public void close() {
        thread.interrupt();
    }
}
