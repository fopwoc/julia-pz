package dev.fopwoc.julia.profiling.agent;

import dev.fopwoc.julia.profiling.run.Scene;
import java.lang.reflect.Method;

/**
 * Drives the game by name, on its logic thread. Game classes aren't on this module's compile path, and a game update
 * that renames something fails here with an error in the batch report instead of a wrong measurement.
 */
final class ReflectiveGame implements Game {
    private static final String MAIN_MENU = "zombie.gameStates.MainScreenState";
    private static final String INGAME = "zombie.gameStates.IngameState";

    private Class<?> type(String name) throws ClassNotFoundException {
        return Class.forName(name, true, ClassLoader.getSystemClassLoader());
    }

    private Object state() throws ReflectiveOperationException {
        var machine = type("zombie.GameWindow").getField("states").get(null);
        return machine == null ? null : machine.getClass().getField("current").get(machine);
    }

    private Object env() throws ReflectiveOperationException {
        return type("zombie.Lua.LuaManager").getField("env").get(null);
    }

    private static Object call(Object target, String method, Object... arguments) throws ReflectiveOperationException {
        for (Method candidate : target.getClass().getMethods()) {
            if (candidate.getName().equals(method) && candidate.getParameterCount() == arguments.length) {
                boolean matches = true;
                var parameters = candidate.getParameterTypes();
                for (int at = 0; at < parameters.length; at++) matches &= accepts(parameters[at], arguments[at]);
                if (matches) return candidate.invoke(target, arguments);
            }
        }
        throw new NoSuchMethodException(target.getClass().getName() + "." + method);
    }

    private static boolean accepts(Class<?> parameter, Object argument) {
        if (parameter == float.class) return argument instanceof Float;
        if (parameter == int.class) return argument instanceof Integer;
        if (parameter == boolean.class) return argument instanceof Boolean;
        return argument == null || parameter.isInstance(argument);
    }

    private void lua(String function, Object... arguments) throws ReflectiveOperationException {
        var manager = type("zombie.Lua.LuaManager");
        var target = manager.getMethod("getFunctionObject", String.class).invoke(null, function);
        if (target == null) throw new NoSuchMethodException("Lua function " + function);
        var caller = manager.getField("caller").get(null);
        var thread = manager.getField("thread").get(null);
        var all = new Object[arguments.length + 2];
        all[0] = thread;
        all[1] = target;
        System.arraycopy(arguments, 0, all, 2, arguments.length);
        call(caller, "pcallvoid", all);
    }

    private Object player() throws ReflectiveOperationException {
        return type("zombie.characters.IsoPlayer").getMethod("getInstance").invoke(null);
    }

    @Override
    public boolean atMainMenu() throws ReflectiveOperationException {
        var state = state();
        if (state == null || !state.getClass().getName().equals(MAIN_MENU)) return false;
        var env = env();
        if (env == null) return false;
        var screen = call(env, "rawget", "MainScreen");
        return screen != null && call(screen, "rawget", "instance") != null;
    }

    /** The game's own continue path, so save validation and the mod-reset flow are the same as a player's. */
    @Override
    public void loadSave(String mode, String world) throws ReflectiveOperationException {
        lua("MainScreen.continueLatestSave", mode, world);
    }

    @Override
    public boolean playerReady() throws ReflectiveOperationException {
        var state = state();
        return state != null && state.getClass().getName().equals(INGAME) && player() != null;
    }

    @Override
    public void arrive(Scene.Spot spot) throws ReflectiveOperationException {
        var player = player();
        // A death would end the run. The game's own tutorial calls this outside debug mode.
        call(player, "setInvincible", true);
        call(player, "setGhostMode", spot.ghost());
        if (spot.position() != null)
            call(
                    player,
                    "teleportTo",
                    spot.position().x(),
                    spot.position().y(),
                    spot.position().z());
        if (spot.hour() != null) {
            var time = type("zombie.GameTime").getMethod("getInstance").invoke(null);
            call(time, "setTimeOfDay", spot.hour().floatValue());
        }
        // Kahlua numbers are always doubles.
        lua("setGameSpeed", Double.valueOf(1));
    }

    @Override
    public boolean arrived(Scene.Spot spot) throws ReflectiveOperationException {
        var player = player();
        var square = call(player, "getCurrentSquare");
        if (square == null) return false;
        var position = spot.position();
        if (position == null) return true;
        return Math.abs((float) call(player, "getX") - position.x()) < 1
                && Math.abs((float) call(player, "getY") - position.y()) < 1
                && Math.floor((float) call(player, "getZ")) == position.z()
                && ((Number) call(square, "getX")).intValue() == (int) Math.floor(position.x())
                && ((Number) call(square, "getY")).intValue() == (int) Math.floor(position.y());
    }

    @Override
    public void quit() throws ReflectiveOperationException {
        call(type("zombie.core.Core").getMethod("getInstance").invoke(null), "quitToDesktop");
    }
}
