package zombie.core;

public final class Core {
    private static final Core INSTANCE = new Core();

    public static Core getInstance() {
        return INSTANCE;
    }

    public int getScreenWidth() {
        return 640;
    }

    public int getScreenHeight() {
        return 480;
    }
}
