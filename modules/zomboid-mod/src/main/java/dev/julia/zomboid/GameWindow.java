package dev.julia.zomboid;

import dev.julia.runtime.ForeignWindow;
import java.util.Locale;

/** Resolves the game's GLFW window to its native platform handle, through the game's own LWJGL classes. */
final class GameWindow {
    private GameWindow() {}

    static ForeignWindow current() throws ReflectiveOperationException {
        long glfwWindow = (long) Class.forName("org.lwjglx.opengl.Display")
                .getMethod("getWindow")
                .invoke(null);
        if (glfwWindow == 0) throw new IllegalStateException("The game has no GLFW window");
        String os = System.getProperty("os.name").toLowerCase(Locale.ROOT);
        if (os.startsWith("mac")) {
            return new ForeignWindow.Cocoa(
                    nativeHandle("org.lwjgl.glfw.GLFWNativeCocoa", "glfwGetCocoaWindow", glfwWindow));
        }
        // Win32 (glfwGetWin32Window) and X11/Wayland handles follow once their backends are ported.
        throw new UnsupportedOperationException("Window presentation is not yet ported to " + os);
    }

    private static long nativeHandle(String owner, String method, long glfwWindow) throws ReflectiveOperationException {
        return (long) Class.forName(owner).getMethod(method, long.class).invoke(null, glfwWindow);
    }
}
