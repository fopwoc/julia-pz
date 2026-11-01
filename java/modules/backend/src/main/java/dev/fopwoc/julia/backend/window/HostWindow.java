package dev.fopwoc.julia.backend.window;

import com.gtnewhorizons.angelica.sdlgpu.device.WindowSource;

/** One borrowed SDL window per isolated runtime, with explicit thread ownership. */
public final class HostWindow implements WindowSource, AutoCloseable {
    private static volatile HostWindow current;
    private final long handle;
    private final Thread owner = Thread.currentThread();
    private int width;
    private int height;
    private boolean resized = true;
    private boolean logicalRendering;

    private HostWindow(long handle, int width, int height) {
        this.handle = handle;
        this.width = width;
        this.height = height;
    }

    public static synchronized HostWindow bind(long handle, int width, int height) {
        if (handle == 0 || width <= 0 || height <= 0)
            throw new IllegalArgumentException("A live window and positive dimensions are required");
        if (current != null) throw new IllegalStateException("A host window is already bound");
        var window = new HostWindow(handle, width, height);
        current = window;
        return window;
    }

    /** Answers 0 once closed, so device teardown after the binding ends releases nothing. */
    @Override
    public long window() {
        return current == this ? handle : 0;
    }

    @Override
    public int width() {
        requireBound();
        return width;
    }

    @Override
    public int height() {
        requireBound();
        return height;
    }

    @Override
    public boolean wasResized() {
        requireBound();
        return resized;
    }

    /** Render in the host's GL coordinate space; the final present scales to the native drawable. */
    public void useLogicalResolution() {
        requireOwner();
        logicalRendering = true;
    }

    @Override
    public long renderSize(long nativePixels) {
        if (nativePixels == 0) return 0;
        requireBound();
        return logicalRendering ? ((long) width << 32) | (height & 0xffffffffL) : nativePixels;
    }

    public long handle() {
        requireBound();
        return handle;
    }

    public void dimensions(int width, int height) {
        requireOwner();
        if (width <= 0 || height <= 0) throw new IllegalArgumentException("Positive dimensions are required");
        resized = this.width != width || this.height != height;
        this.width = width;
        this.height = height;
    }

    private void requireBound() {
        requireOwner();
        if (current != this) throw new IllegalStateException("Host window binding is closed");
    }

    private void requireOwner() {
        if (Thread.currentThread() != owner)
            throw new IllegalStateException("Host window belongs to " + owner.getName());
    }

    @Override
    public void close() {
        requireOwner();
        if (current == this) current = null;
    }
}
