package zombie;

/** Shape of the game's logic owner, for the instrumentation test. */
public final class GameWindow {
    private static int steps;

    private static void frameStep() {
        steps++;
    }

    public static int step() {
        frameStep();
        return steps;
    }
}
