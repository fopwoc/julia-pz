package dev.fopwoc.julia.backend.window.windows;

import static org.lwjgl.sdl.SDLProperties.SDL_SetPointerProperty;
import static org.lwjgl.sdl.SDLVideo.SDL_PROP_WINDOW_CREATE_WIN32_HWND_POINTER;
import static org.lwjgl.system.windows.User32.*;

import dev.fopwoc.julia.backend.utils.Sdl;
import dev.fopwoc.julia.backend.window.BorrowedWindow;
import dev.fopwoc.julia.backend.window.SdlBorrowedWindow;

/** SDL may inspect the HWND, but GLFW keeps its original event procedure. */
public final class Win32BorrowedWindow implements BorrowedWindow {
    private final long hwnd;
    private final long procedure;
    private SdlBorrowedWindow window;

    public Win32BorrowedWindow(long hwnd) {
        this.hwnd = hwnd;
        procedure = GetWindowLongPtr(hwnd, GWL_WNDPROC);
        if (procedure == 0) throw new IllegalStateException("Cannot read the GLFW window procedure");
        try {
            window = SdlBorrowedWindow.create(
                    "Wrap Win32 window",
                    properties -> Sdl.require(
                            SDL_SetPointerProperty(properties, SDL_PROP_WINDOW_CREATE_WIN32_HWND_POINTER, hwnd),
                            "Set foreign HWND"));
            restore();
        } catch (RuntimeException | Error failure) {
            close();
            throw failure;
        }
    }

    private void restore() {
        SetWindowLongPtr(null, hwnd, GWL_WNDPROC, procedure);
        if (GetWindowLongPtr(hwnd, GWL_WNDPROC) != procedure)
            throw new IllegalStateException("GLFW window procedure was not restored");
    }

    @Override
    public long handle() {
        return window.handle();
    }

    @Override
    public void close() {
        try {
            if (window != null) {
                window.close();
                window = null;
            }
        } finally {
            restore();
        }
    }
}
