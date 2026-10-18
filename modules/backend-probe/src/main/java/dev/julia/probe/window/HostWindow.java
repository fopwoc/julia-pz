package dev.julia.probe.window;

/** One borrowed SDL window per isolated runtime, with explicit thread ownership. */
public final class HostWindow implements AutoCloseable {
    private static volatile HostWindow current;
    private final long handle;
    private final Thread owner = Thread.currentThread();
    private int width;
    private int height;
    private boolean resized = true;

    private HostWindow(long handle, int width, int height) {
        this.handle = handle;
        this.width = width;
        this.height = height;
    }

    public static synchronized HostWindow bind(long handle, int width, int height) {
        if (handle == 0 || width <= 0 || height <= 0) throw new IllegalArgumentException("A live window and positive dimensions are required");
        if (current != null) throw new IllegalStateException("A host window is already bound");
        var window = new HostWindow(handle, width, height);
        current = window;
        return window;
    }

    public static long getWindow() {
        HostWindow window = current;
        return window == null ? 0 : window.handle;
    }
    public static int getWidth() { return require().width; }
    public static int getHeight() { return require().height; }
    public static boolean wasResized() { return require().resized; }

    public long handle() {
        requireOwner();
        if (current != this) throw new IllegalStateException("Host window binding is closed");
        return handle;
    }

    public void dimensions(int width, int height) {
        requireOwner();
        if (width <= 0 || height <= 0) throw new IllegalArgumentException("Positive dimensions are required");
        resized = this.width != width || this.height != height;
        this.width = width;
        this.height = height;
    }

    private static HostWindow require() {
        HostWindow window = current;
        if (window == null) throw new IllegalStateException("No host window is bound");
        window.requireOwner();
        return window;
    }

    private void requireOwner() {
        if (Thread.currentThread() != owner) throw new IllegalStateException("Host window belongs to " + owner.getName());
    }

    @Override
    public void close() {
        requireOwner();
        if (current == this) current = null;
    }
}
