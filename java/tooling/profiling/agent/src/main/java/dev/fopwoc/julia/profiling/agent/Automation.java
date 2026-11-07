package dev.fopwoc.julia.profiling.agent;

import dev.fopwoc.julia.profiling.run.Scene;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.function.Supplier;

/**
 * Takes the game through a scene: load the save, visit each spot, record it, quit. Ticks arrive on the game's logic
 * thread, which owns Lua and the world. Progress goes to {@code automation.txt}, which the batch waits on.
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

    private enum Stage {
        MENU,
        LOADING,
        POSITIONING,
        RECORDING,
        OVER
    }

    private final Scene scene;
    private final Game game;
    private final Recorder recorder;
    private final Path status;
    private final boolean julia;
    private final Supplier<String> backend;
    private Stage stage = Stage.MENU;
    private int spot;
    private long nextPoll;

    Automation(Scene scene, Game game, Recorder recorder, Path status, boolean julia, Supplier<String> backend) {
        this.scene = scene;
        this.game = game;
        this.recorder = recorder;
        this.status = status;
        this.julia = julia;
        this.backend = backend;
    }

    void tick(long now) {
        if (stage == Stage.OVER || now - nextPoll < 0) return;
        nextPoll = now + POLL_NANOS;
        try {
            step();
        } catch (Exception | LinkageError failure) {
            fail(failure.toString());
        }
    }

    private void step() throws ReflectiveOperationException, IOException {
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
                if (game.arrived(current())) {
                    requireRenderer();
                    recorder.record(current().name(), scene.warmup(), scene.duration());
                    stage = Stage.RECORDING;
                    report("RECORDING " + current().name());
                }
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

    private void arrive() throws ReflectiveOperationException, IOException {
        game.arrive(current());
        stage = Stage.POSITIONING;
        report("POSITIONING " + current().name());
    }

    private Scene.Spot current() {
        return scene.spots().get(spot);
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

    private void report(String value) throws IOException {
        Files.writeString(status, value + "\n");
    }
}
