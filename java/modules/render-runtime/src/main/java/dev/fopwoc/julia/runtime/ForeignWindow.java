package dev.fopwoc.julia.runtime;

import java.util.Objects;
import java.util.function.LongSupplier;

/**
 * A native window owned by the game, described with JDK types only so it can cross class-loader boundaries.
 *
 * <p>The game keeps ownership of the window, its event loop, and input; the graphics runtime only presents into it.
 */
public sealed interface ForeignWindow {
    /** macOS {@code NSWindow*}. */
    record Cocoa(long window) implements ForeignWindow {
        public Cocoa {
            requireHandle(window, "NSWindow");
        }
    }

    /** Windows {@code HWND}. */
    record Win32(long hwnd) implements ForeignWindow {
        public Win32 {
            requireHandle(hwnd, "HWND");
        }
    }

    /** X11 {@code Display*} and {@code Window} id. */
    record X11(long display, long window) implements ForeignWindow {
        public X11 {
            requireHandle(display, "X11 display");
            requireHandle(window, "X11 window");
        }
    }

    /** Wayland {@code wl_display*} and {@code wl_surface*}. */
    record Wayland(long display, long surface, LongSupplier pixels) implements ForeignWindow {
        public Wayland {
            requireHandle(display, "Wayland display");
            requireHandle(surface, "Wayland surface");
            Objects.requireNonNull(pixels, "Wayland drawable dimensions");
        }
    }

    private static void requireHandle(long handle, String kind) {
        if (handle == 0) throw new IllegalArgumentException(kind + " handle is null");
    }
}
