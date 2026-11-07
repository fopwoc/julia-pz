package dev.fopwoc.julia.backend.runtime;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;

/**
 * Debug record of the base game's UI redraw decision, beside the draw trace. The trace only shows the GL calls that
 * result, so it cannot say why a frame skipped redrawing the UI layer.
 *
 * <p>Values are read on the render thread while the game thread may be writing them, so a sample can be one frame off.
 * Names are resolved by reflection because backend code never links against game classes.
 */
final class GameUiStateLog {
    private static final System.Logger LOG = System.getLogger(GameUiStateLog.class.getName());
    // The early frames run during game class initialization, and resolving these classes could initialize them from
    // the render thread while the game thread is waiting on it.
    private static final long FIRST_FRAME = 600;
    private static final long FRAME_INTERVAL = 30;
    private static final int MAX_SAMPLES = 7200;

    private final Path file;
    private List<Field> fields;
    private int samples;
    private boolean stopped;

    private record Field(String name, Callable<Object> read) {}

    private GameUiStateLog(Path file) {
        this.file = file;
    }

    /** Returns null unless the draw trace is enabled, so normal play does no reflection at all. */
    static GameUiStateLog open(Path output) {
        return Boolean.getBoolean("julia.drawTrace") ? new GameUiStateLog(output.resolve("ui-state.txt")) : null;
    }

    void presented(long frame) {
        if (stopped || frame < FIRST_FRAME || frame % FRAME_INTERVAL != 0) return;
        try {
            if (fields == null) fields = resolve();
            var line = new StringBuilder(Instant.now().toString())
                    .append(" frame=")
                    .append(frame);
            for (var field : fields)
                line.append(' ').append(field.name).append('=').append(field.read.call());
            Files.writeString(file, line.append('\n'), StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            stopped = ++samples >= MAX_SAMPLES;
        } catch (Exception | LinkageError failure) {
            // A debug record must never take the game down with it.
            stopped = true;
            LOG.log(System.Logger.Level.WARNING, "Stopped recording game UI state", failure);
        }
    }

    private static List<Field> resolve() throws ReflectiveOperationException {
        var loader = ClassLoader.getSystemClassLoader();
        var core = Class.forName("zombie.core.Core", true, loader);
        var ui = Class.forName("zombie.ui.UIManager", true, loader);
        var time = Class.forName("zombie.GameTime", true, loader);
        var performance = Class.forName("zombie.core.PerformanceSettings", true, loader);
        var window = Class.forName("zombie.GameWindow", true, loader);
        var machine = Class.forName("zombie.gameStates.GameStateMachine", true, loader);

        var coreInstance = core.getMethod("getInstance");
        var timeInstance = time.getMethod("getInstance");
        var result = new ArrayList<Field>();
        result.add(staticField("useUiFbo", ui));
        result.add(staticField("uiTextureContentsValid", ui));
        result.add(instanceField("uiRenderThisFrame", core, coreInstance));
        result.add(instanceField("uiRenderAccumulator", core, coreInstance));
        result.add(instanceMethod("getOptionUIRenderFPS", core, coreInstance));
        result.add(instanceMethod("getMultiplier", time, timeInstance));
        result.add(instanceMethod("getThirtyFPSMultiplier", time, timeInstance));
        result.add(staticMethod("isGamePaused", time));
        result.add(staticMethod("getLockFPS", performance));
        result.add(staticMethod("isFramerateUncapped", performance));
        var states = window.getField("states");
        var current = machine.getField("current");
        result.add(new Field("state", () -> {
            var state = current.get(states.get(null));
            return state == null ? null : state.getClass().getSimpleName();
        }));
        return result;
    }

    private static Field staticField(String name, Class<?> owner) throws NoSuchFieldException {
        var field = owner.getField(name);
        return new Field(name, () -> field.get(null));
    }

    private static Field instanceField(String name, Class<?> owner, Method instance) throws NoSuchFieldException {
        var field = owner.getField(name);
        return new Field(name, () -> field.get(instance.invoke(null)));
    }

    private static Field staticMethod(String name, Class<?> owner) throws NoSuchMethodException {
        var method = owner.getMethod(name);
        return new Field(name, () -> method.invoke(null));
    }

    private static Field instanceMethod(String name, Class<?> owner, Method instance) throws NoSuchMethodException {
        var method = owner.getMethod(name);
        return new Field(name, () -> method.invoke(instance.invoke(null)));
    }
}
