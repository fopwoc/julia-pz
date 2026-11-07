package dev.fopwoc.julia.profiling.agent;

import dev.fopwoc.julia.profiling.core.CapturePlan;
import dev.fopwoc.julia.profiling.core.CaptureSession;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.text.ParseException;
import java.time.Duration;
import java.util.Properties;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import jdk.jfr.Configuration;
import jdk.jfr.Recording;

/** Owns recordings: the frame hook fills them, and this thread flushes them so no file IO lands on a frame. */
final class CaptureController implements AutoCloseable, Automation.Recorder {
    private static final int MAX_SAMPLES = 1_000_000;
    private static final System.Logger LOG = System.getLogger(CaptureController.class.getName());
    private final Path directory;
    private final ScheduledExecutorService control = Executors.newSingleThreadScheduledExecutor(task -> {
        var thread = new Thread(task, "profiling-control");
        thread.setDaemon(true);
        return thread;
    });
    private static final String COUNTERS = "angelica.debug.counters";
    private static final String COUNTER_LABEL = "angelica.debug.counters.label";
    private static final String JAVA_PROFILE = "profile.java";
    private String captureName;
    private Recording flight;
    private String lastResult;
    private CaptureSession active;
    private boolean ready;
    private byte[] settings;
    private long gcCount, gcTime;

    CaptureController(Path directory) throws IOException {
        this.directory = directory;
        Files.createDirectories(directory.resolve("captures"));
        var environment = new Properties();
        for (String key : new String[] {"java.version", "java.vm.name", "os.name", "os.arch", "sun.java.command"})
            environment.setProperty(key, System.getProperty(key, "unknown"));
        environment.setProperty(
                "profiler.version",
                String.valueOf(ProfilerAgent.class.getPackage().getImplementationVersion()));
        environment.setProperty(
                "jvm.arguments",
                String.join(" ", ManagementFactory.getRuntimeMXBean().getInputArguments()));
        try (var writer =
                Files.newBufferedWriter(directory.resolve("environment.properties"), StandardOpenOption.CREATE_NEW)) {
            environment.store(writer, "Profiling environment");
        }
        status("WAITING_FOR_FRAME_OWNER");
    }

    void start() {
        control.scheduleWithFixedDelay(this::pollSafely, 0, 200, TimeUnit.MILLISECONDS);
    }

    synchronized void status(String value) throws IOException {
        Files.writeString(directory.resolve("status.txt"), value + "\n");
    }

    synchronized void frameOwnerReady() throws IOException {
        ready = true;
        status("READY");
    }

    private synchronized void pollSafely() {
        try {
            flush();
        } catch (RuntimeException | IOException failure) {
            control.shutdown();
            if (active != null) active.stop();
            ProfilingHooks.capture(null);
            lastResult = "ERROR";
            LOG.log(System.Logger.Level.ERROR, "Profiling control failed; recording disabled", failure);
            try {
                status("ERROR " + failure);
            } catch (IOException report) {
                failure.addSuppressed(report);
            }
        }
    }

    @Override
    public synchronized void record(String name, Duration warmup, Duration duration) throws IOException {
        try {
            arm(name, warmup, duration);
        } catch (ParseException failure) {
            throw new IOException("Unreadable profiling settings", failure);
        }
    }

    private void arm(String name, Duration warmup, Duration duration) throws IOException, ParseException {
        if (!ready) throw new IllegalStateException("The frame hook is not installed yet");
        if (active != null) throw new IllegalStateException("A recording is already active");
        captureName = name;
        lastResult = null;
        Path options = directory.resolve("profile/options.ini");
        settings = Files.isRegularFile(options) ? Files.readAllBytes(options) : null;
        gcCount = gc(false);
        gcTime = gc(true);
        if (Boolean.getBoolean(COUNTERS)) System.setProperty(COUNTER_LABEL, name);
        if (Boolean.getBoolean(JAVA_PROFILE)) {
            flight = new Recording(Configuration.getConfiguration("profile"));
            flight.start();
        }
        active = new CaptureSession(new CapturePlan(name, warmup, duration), MAX_SAMPLES);
        ProfilingHooks.capture(active);
        status("ARMED " + captureName);
    }

    @Override
    public synchronized String finished() {
        return lastResult;
    }

    private void flush() throws IOException {
        if (active == null || active.active()) return;
        long completedGcCount = gc(false), completedGcTime = gc(true);
        var result = active.result();
        result.write(directory.resolve("captures").resolve(captureName));
        if (settings != null)
            Files.write(directory.resolve("captures").resolve(captureName).resolve("options.ini"), settings);
        var metrics = new Properties();
        metrics.setProperty("gc_collections", Long.toString(completedGcCount - gcCount));
        metrics.setProperty("gc_time_ms", Long.toString(completedGcTime - gcTime));
        metrics.setProperty("gc_scope", "whole JVM from arming to completion polling; includes warmup");
        try (var writer = Files.newBufferedWriter(
                directory.resolve("captures").resolve(captureName).resolve("gc.properties"))) {
            metrics.store(writer, "GC counters");
        }
        active = null;
        ProfilingHooks.capture(null);
        System.clearProperty(COUNTER_LABEL);
        if (flight != null) {
            flight.dump(directory.resolve("captures").resolve(captureName).resolve("profile.jfr"));
            flight.close();
            flight = null;
        }
        status(result.state() + " " + captureName);
        lastResult = result.state().toString();
    }

    private static long gc(boolean time) {
        return ManagementFactory.getGarbageCollectorMXBeans().stream()
                .mapToLong(bean -> Math.max(0, time ? bean.getCollectionTime() : bean.getCollectionCount()))
                .sum();
    }

    @Override
    public synchronized void close() throws IOException {
        control.shutdownNow();
        if (active != null) active.stop();
        flush();
    }
}
