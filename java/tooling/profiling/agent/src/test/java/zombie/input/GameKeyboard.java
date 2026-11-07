package zombie.input;

/** Shape of the game's input class, with locals so the class carries the debug tables the real one has. */
public final class GameKeyboard {
    private static int presses;

    public static void update() {
        int next = presses + 1;
        presses = next;
    }
}
