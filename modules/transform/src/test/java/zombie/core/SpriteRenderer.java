package zombie.core;

/** Synthetic host fixture, not game code. */
public final class SpriteRenderer {
    public static final SpriteRenderer instance = new SpriteRenderer();
    public static boolean fail;
    public static int draws;
    public void postRender() {
        draws++;
        if (fail) throw new IllegalStateException("synthetic draw failure");
    }
}
