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
 * discards the JVM's own thread dumps, so this report is the only way a player can show where rendering is stuck. It
 * never stops the game.
 */
final class FrameWatchdog implements AutoCloseable {
    private static final System.Logger LOG = System.getLogger(FrameWatchdog.class.getName());
    private static final int MAX_REPORTS = 3;

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
        int reports = 0;
        Path stalled = null;
        long stallStart = 0;
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
                    stallStart = lastPresent;
                    stalled = output.resolve("hang-" + reports + ".txt");
                    write(stalled, Duration.ofNanos(since));
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
