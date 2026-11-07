package dev.fopwoc.julia.profiling.agent;

import dev.fopwoc.julia.profiling.run.Scene;
import java.lang.reflect.Method;
import java.util.List;

/**
 * Drives the game by name, on its logic thread. Game classes aren't on this module's compile path, and a game update
 * that renames something fails here with an error in the batch report instead of a wrong measurement.
 */
final class ReflectiveGame implements Game {
    private static final String MAIN_MENU = "zombie.gameStates.MainScreenState";
    private static final String INGAME = "zombie.gameStates.IngameState";

    private static final int PANEL_PADDING = 10;

    private Object textManager;
    private Object smallFont;
    private Object sprites;
    private Object white;
    private Object core;
    private Method drawString;
    private Method measure;
    private Method fontHeight;

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
        // The game's menu only offers this with a system flag; a capped run shows nothing about throughput.
        var performance = type("zombie.core.PerformanceSettings");
        call(performance.getField("instance").get(null), "setFramerateUncapped", true);
        keepAlive();
        // Like the setters above, setGhostMode is role-guarded. Being invisible is what stops zombies reacting.
        cheat(player, "INVISIBLE", spot.ghost());
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
    public String position() throws ReflectiveOperationException {
        var player = player();
        if (player == null) return "no player";
        var square = call(player, "getCurrentSquare");
        return call(player, "getX") + "," + call(player, "getY") + "," + call(player, "getZ") + " square "
                + (square == null
                        ? "none"
                        : call(square, "getX") + "," + call(square, "getY") + "," + call(square, "getZ"));
    }

    @Override
    public int zoomOut() throws ReflectiveOperationException {
        var coreType = type("zombie.core.Core");
        var instance = coreType.getMethod("getInstance").invoke(null);
        var widest = (float) call(instance, "getMaxZoom");
        call(coreType.getField("offscreenBuffer").get(instance), "setZoomAndTargetZoom", 0, widest);
        return Math.round(widest * 100);
    }

    @Override
    public void face(float degrees) throws ReflectiveOperationException {
        call(player(), "setDirectionAngle", degrees);
    }

    /**
     * The public setters do nothing without a debug or admin role, so this sets the cheats directly. God mode alone isn't
     * enough, since not every way a zombie kills goes through the damage it guards. Zombies still chase and crowd the
     * character, so their load stays in the numbers.
     */
    @Override
    public void keepAlive() throws ReflectiveOperationException {
        var player = player();
        if (player == null) return;
        if ((boolean) call(player, "isDead"))
            throw new IllegalStateException("The character died; these numbers aren't valid");
        cheat(player, "GOD_MODE", true);
        cheat(player, "ZOMBIES_DONT_ATTACK", true);
        // Being dragged down by a crowd kills through neither of the above. It's a sandbox rule, and this run's copy of
        // it.
        var options = type("zombie.SandboxOptions").getField("instance").get(null);
        var lore = options.getClass().getField("lore").get(options);
        call(lore.getClass().getField("zombiesDragDown").get(lore), "setValue", false);
        forceField(player, "invincible", true);
        call(call(player, "getBodyDamage"), "RestoreToFullHealth");
    }

    private void cheat(Object player, String name, boolean value) throws ReflectiveOperationException {
        var flag = type("zombie.characters.CheatType").getField(name).get(null);
        call(call(player, "getCheats"), "set", flag, value);
    }

    @Override
    public void drawPanel(List<String> lines) throws ReflectiveOperationException {
        if (textManager == null) {
            var textType = type("zombie.ui.TextManager");
            var fontType = type("zombie.ui.UIFont");
            textManager = textType.getField("instance").get(null);
            smallFont = fontType.getField("Small").get(null);
            drawString = textType.getMethod(
                    "DrawString",
                    fontType,
                    double.class,
                    double.class,
                    String.class,
                    double.class,
                    double.class,
                    double.class,
                    double.class);
            measure = textType.getMethod("MeasureStringX", fontType, String.class);
            fontHeight = textType.getMethod("getFontHeight", fontType);
            sprites = type("zombie.core.SpriteRenderer").getField("instance").get(null);
            white = type("zombie.core.textures.Texture").getMethod("getWhite").invoke(null);
            core = type("zombie.core.Core").getMethod("getInstance").invoke(null);
        }
        int lineHeight = (int) fontHeight.invoke(textManager, smallFont);
        int width = 0;
        for (var line : lines) width = Math.max(width, (int) measure.invoke(textManager, smallFont, line));
        int height = lineHeight * lines.size();
        int x = ((int) call(core, "getScreenWidth") - width) / 2;
        int y = ((int) call(core, "getScreenHeight") - height) / 2;
        call(
                sprites,
                "renderi",
                white,
                x - PANEL_PADDING,
                y - PANEL_PADDING,
                width + 2 * PANEL_PADDING,
                height + 2 * PANEL_PADDING,
                0f,
                0f,
                0f,
                0.75f,
                null);
        for (int at = 0; at < lines.size(); at++)
            drawString.invoke(
                    textManager, smallFont, (double) x, (double) (y + at * lineHeight), lines.get(at), 1d, 1d, 1d, 1d);
    }

    private static void forceField(Object target, String name, boolean value) throws ReflectiveOperationException {
        for (Class<?> owner = target.getClass(); owner != null; owner = owner.getSuperclass()) {
            for (var field : owner.getDeclaredFields()) {
                if (field.getName().equals(name) && field.getType() == boolean.class) {
                    field.setAccessible(true);
                    field.setBoolean(target, value);
                    return;
                }
            }
        }
        throw new NoSuchFieldException(target.getClass().getName() + "." + name);
    }

    @Override
    public void quit() throws ReflectiveOperationException {
        call(type("zombie.core.Core").getMethod("getInstance").invoke(null), "quitToDesktop");
    }
}
