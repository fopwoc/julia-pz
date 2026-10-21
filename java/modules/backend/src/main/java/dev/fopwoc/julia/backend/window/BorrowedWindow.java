package dev.fopwoc.julia.backend.window;

import dev.fopwoc.julia.backend.window.linux.WaylandBorrowedWindow;
import dev.fopwoc.julia.backend.window.linux.X11BorrowedWindow;
import dev.fopwoc.julia.backend.window.macos.CocoaBorrowedWindow;
import dev.fopwoc.julia.backend.window.windows.Win32BorrowedWindow;
import dev.fopwoc.julia.runtime.ForeignWindow;

/**
 * An SDL window wrapped around a game-owned native window.
 *
 * <p>Implementations own only the SDL wrapper: closing it must leave the native window, its view hierarchy, event
 * routing, and input exactly as the game configured them. Requires SDL video to be initialized.
 */
public interface BorrowedWindow extends AutoCloseable {
    /** The SDL window handle presenting into the borrowed native window. */
    long handle();

    @Override
    void close();

    static BorrowedWindow borrow(ForeignWindow window) {
        return switch (window) {
            case ForeignWindow.Cocoa cocoa -> new CocoaBorrowedWindow(cocoa.window());
            case ForeignWindow.Win32 win32 -> new Win32BorrowedWindow(win32.hwnd());
            case ForeignWindow.X11 x11 -> X11BorrowedWindow.borrow(x11.window());
            case ForeignWindow.Wayland wayland -> new WaylandBorrowedWindow(wayland);
        };
    }
}
