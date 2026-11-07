package dev.fopwoc.julia.profiling.agent;

import dev.fopwoc.julia.profiling.run.Scene;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Supplier;

/**
 * Takes the game through a scene: load the save, visit every spot once to look around, then visit each again to record it,
 * and quit. The first pass gets shaders, textures and models ready, so no spot pays for being reached first. Ticks arrive on the game's
 * logic thread, which owns Lua and the world. Progress goes to {@code automation.txt}, which the batch waits on.
 */
final class Automation {
    /** The recording side: a capture is requested, then polled until it reports how it ended. */
    interface Recorder {
        void record(String name, Duration warmup, Duration duration) throws IOException;

        /** {@code null} while recording, otherwise the final state. */
        String finished();
    }

    private static final System.Logger LOG = System.getLogger(Automation.class.getName());
    private static final long POLL_NANOS = Duration.ofMillis(200).toNanos();
    private static final long SECOND = Duration.ofSeconds(1).toNanos();
    private static final long SCREAM_NANOS = Duration.ofSeconds(3).toNanos();
    private static final long RETELEPORT_NANOS = Duration.ofSeconds(5).toNanos();
    private static final long ARRIVAL_TIMEOUT_NANOS = Duration.ofSeconds(60).toNanos();
    // One full turn, since the fog only clears where the character looks.
    private static final long SURVEY_NANOS = Duration.ofSeconds(4).toNanos();
    private static final float SURVEY_DEGREES_PER_SECOND = 90;

    private enum Stage {
        MENU("menu"),
        LOADING("loading"),
        POSITIONING("moving"),
        SURVEYING("warming up"),
        RECORDING("recording"),
        OVER("done");

        private final String label;

        Stage(String label) {
            this.label = label;
        }
    }

    private final Scene scene;
    private final Game game;
    private final Recorder recorder;
    private final Path status;
    private final boolean julia;
    private final Supplier<String> backend;
    private final String label;
    private final Supplier<String> gpu;
    private volatile Stage stage = Stage.MENU;
    private volatile int spot;
    private volatile boolean warmingUp = true;
    private long nextPoll;
    private long stageStart;
    private long lastTeleport;
    private long lastScream;
    private volatile long recordingStart;
    private volatile long fps;
    private volatile int zoom;
    private long fpsFrames;
    private long fpsStart;
    private long runStart;
    private long runFrames;

    Automation(
            Scene scene,
            Game game,
            Recorder recorder,
            Path status,
            boolean julia,
            Supplier<String> backend,
            String label,
            Supplier<String> gpu) {
        this.scene = scene;
        this.game = game;
        this.recorder = recorder;
        this.status = status;
        this.julia = julia;
        this.backend = backend;
        this.label = label;
        this.gpu = gpu;
    }

    void tick(long now) {
        if (stage == Stage.OVER || now - nextPoll < 0) return;
        nextPoll = now + POLL_NANOS;
        try {
            if (stage.ordinal() >= Stage.POSITIONING.ordinal()) {
                game.keepAlive();
                screamAt(now);
            }
            step(now);
        } catch (Exception | LinkageError failure) {
            fail(failure.toString());
        }
    }

    private void step(long now) throws ReflectiveOperationException, IOException {
        switch (stage) {
            case MENU -> {
                if (game.atMainMenu()) {
                    // Set first: the load can reset Lua and re-enter the menu, and it must not be asked for twice.
                    stage = Stage.LOADING;
                    report("LOADING");
                    game.loadSave(scene.mode(), scene.world());
                }
            }
            case LOADING -> {
                if (game.playerReady()) arrive();
            }
            case POSITIONING -> {
                if (!game.arrived(current())) {
                    long waited = now - stageStart;
                    // Turning from the moment of the teleport looks continuous; the full turn still follows arrival.
                    if (warmingUp) game.face(SURVEY_DEGREES_PER_SECOND * waited / SECOND % 360);
                    if (waited > ARRIVAL_TIMEOUT_NANOS)
                        throw new IllegalStateException(
                                "Can't reach " + current().name() + ": at " + game.position());
                    report("POSITIONING " + current().name() + ": at " + game.position());
                    // A teleport into chunks that haven't loaded yet doesn't always stick.
                    if (now - lastTeleport >= RETELEPORT_NANOS) {
                        game.arrive(current());
                        lastTeleport = now;
                    }
                    return;
                }
                if (warmingUp) {
                    stage = Stage.SURVEYING;
                    stageStart = now;
                    report("SURVEYING " + current().name());
                } else {
                    requireRenderer();
                    writeMachine();
                    recorder.record(current().name(), scene.warmup(), scene.duration());
                    recordingStart = now;
                    stage = Stage.RECORDING;
                    report("RECORDING " + current().name());
                }
            }
            case SURVEYING -> {
                long elapsed = now - stageStart;
                if (elapsed < SURVEY_NANOS) {
                    game.face(SURVEY_DEGREES_PER_SECOND * elapsed / SECOND % 360);
                    return;
                }
                if (++spot == scene.spots().size()) {
                    spot = 0;
                    warmingUp = false;
                }
                arrive();
            }
            case RECORDING -> {
                var result = recorder.finished();
                if (result == null) return;
                if (!result.equals("COMPLETE")) throw new IllegalStateException("Recording ended " + result);
                if (++spot < scene.spots().size()) arrive();
                else {
                    stage = Stage.OVER;
                    report("DONE");
                    game.quit();
                }
            }
            case OVER -> {}
        }
    }

    private void screamAt(long now) throws ReflectiveOperationException {
        if (!current().scream() || warmingUp || now - lastScream < SCREAM_NANOS) return;
        lastScream = now;
        game.scream();
    }

    private void arrive() throws ReflectiveOperationException, IOException {
        game.arrive(current());
        stageStart = nextPoll;
        lastTeleport = nextPoll;
        // Reapplied at each spot in case the game moves it, for example while loading.
        zoom = game.zoomOut();
        stage = Stage.POSITIONING;
        report("POSITIONING " + current().name());
    }

    private Scene.Spot current() {
        return scene.spots().get(spot);
    }

    /** Called from the game's UI pass. A failed panel must never fail the run it's describing. */
    void overlay(long now, long frames) {
        if (!scene.overlay()) return;
        try {
            if (runStart == 0) {
                runStart = now;
                runFrames = frames;
            }
            if (now - fpsStart >= SECOND) {
                if (fpsStart != 0) fps = (frames - fpsFrames) * SECOND / (now - fpsStart);
                fpsStart = now;
                fpsFrames = frames;
            }
            game.drawPanel(panel(now, frames));
        } catch (Exception | LinkageError failure) {
            LOG.log(System.Logger.Level.DEBUG, "Cannot draw the status panel", failure);
        }
    }

    List<String> panel(long now, long frames) {
        var active = backend.get();
        var current = stage;
        var lines = new ArrayList<String>();
        lines.add(label);
        lines.add("gpu      " + gpu.get() + (active == null ? "" : " | " + active));
        if (current != Stage.MENU && current != Stage.LOADING)
            lines.add(String.format(
                    Locale.ROOT,
                    "spot     %s (%d/%d)",
                    current().name(),
                    spot + 1,
                    scene.spots().size()));
        var state = new StringBuilder("stage    ")
                .append(warmingUp || current != Stage.POSITIONING ? current.label : "settling");
        if (current == Stage.RECORDING) {
            long elapsed = (now - recordingStart) / SECOND;
            long warmup = scene.warmup().toSeconds();
            if (elapsed < warmup) state.append(String.format(Locale.ROOT, " (settling %d/%d s)", elapsed, warmup));
            else
                state.append(String.format(
                        Locale.ROOT,
                        " %d/%d s",
                        elapsed - warmup,
                        scene.duration().toSeconds()));
        }
        lines.add(state.toString());
        long seconds = (now - runStart) / SECOND;
        long average = seconds > 0 ? (frames - runFrames) / seconds : 0;
        lines.add(String.format(Locale.ROOT, "FPS      %d (avg %d)", fps, average));
        lines.add(String.format(Locale.ROOT, "elapsed  %d:%02d", seconds / 60, seconds % 60));
        lines.add(String.format(
                Locale.ROOT,
                "args     zoom %d%%, heap %d MB, ZB %s, Steam %s",
                zoom,
                Runtime.getRuntime().maxMemory() / (1024 * 1024),
                scene.zombieBuddy() ? "on" : "off",
                scene.steam() ? "on" : "off"));
        return lines;
    }

    /** A run that quietly ended up on the wrong renderer would compare one renderer with itself. */
    private void requireRenderer() {
        var active = backend.get();
        if (julia && active == null) throw new IllegalStateException("Julia is not rendering this run");
        if (!julia && active != null) throw new IllegalStateException("The baseline run started Julia on " + active);
    }

    private void fail(String reason) {
        stage = Stage.OVER;
        LOG.log(System.Logger.Level.ERROR, "Profiling automation failed: {0}", reason);
        try {
            report("ERROR " + reason);
        } catch (IOException failure) {
            LOG.log(System.Logger.Level.ERROR, "Cannot report automation failure", failure);
        }
        // The batch stops this process once it reads the error.
    }

    private void writeMachine() throws ReflectiveOperationException, IOException {
        if (spot == 0)
            Files.writeString(status.resolveSibling("machine.txt"), gpu.get() + " | " + game.machine() + "\n");
    }

    private void report(String value) throws IOException {
        Files.writeString(status, value + "\n");
    }
}
