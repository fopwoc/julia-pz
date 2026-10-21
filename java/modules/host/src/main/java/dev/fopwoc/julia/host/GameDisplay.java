package dev.fopwoc.julia.host;

import dev.fopwoc.julia.runtime.ForeignWindow;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.IntBuffer;

/** Uses the game's GLFW binding and republishes its native GL dispatch after isolated initialization/teardown. */
final class GameDisplay {
    private final ClassLoader host;
    private final Object capabilities;
    private final Method publish;

    GameDisplay(ClassLoader host) throws ReflectiveOperationException {
        this.host = host;
        var gl = Class.forName("org.lwjgl.opengl.GL", true, host);
        var get = gl.getMethod("getCapabilities");
        capabilities = get.invoke(null);
        publish = gl.getMethod("setCapabilities", get.getReturnType());
    }

    /** The native window behind PZ's GLFW window, read through the game's own GLFW native bindings. */
    ForeignWindow window() throws ReflectiveOperationException {
        long glfw = (long) Class.forName("org.lwjglx.opengl.Display", true, host)
                .getMethod("getWindow")
                .invoke(null);
        if (glfw == 0) throw new IllegalStateException("First graphics call arrived before PZ created its GLFW window");
        String os = System.getProperty("os.name");
        if (os.startsWith("Mac")) return new ForeignWindow.Cocoa(handle("GLFWNativeCocoa", "glfwGetCocoaWindow", glfw));
        if (os.startsWith("Windows"))
            return new ForeignWindow.Win32(handle("GLFWNativeWin32", "glfwGetWin32Window", glfw));
        if (os.startsWith("Linux")) return wayland() ? waylandWindow(glfw) : x11Window(glfw);
        throw new UnsupportedOperationException("No native window binding for " + os);
    }

    private boolean wayland() throws ReflectiveOperationException {
        var glfw = Class.forName("org.lwjgl.glfw.GLFW", true, host);
        Method platform;
        try {
            platform = glfw.getMethod("glfwGetPlatform");
        } catch (NoSuchMethodException older) {
            // GLFW before 3.4 has no platform query and only supports X11 on Linux.
            return false;
        }
        return (int) platform.invoke(null)
                == glfw.getField("GLFW_PLATFORM_WAYLAND").getInt(null);
    }

    private ForeignWindow x11Window(long glfw) throws ReflectiveOperationException {
        return new ForeignWindow.X11(
                handle("GLFWNativeX11", "glfwGetX11Display"), handle("GLFWNativeX11", "glfwGetX11Window", glfw));
    }

    private ForeignWindow waylandWindow(long glfw) throws ReflectiveOperationException {
        var binding = Class.forName("org.lwjgl.glfw.GLFW", true, host);
        var query = binding.getMethod("glfwGetFramebufferSize", long.class, IntBuffer.class, IntBuffer.class);
        var width = ByteBuffer.allocateDirect(4).order(ByteOrder.nativeOrder()).asIntBuffer();
        var height = ByteBuffer.allocateDirect(4).order(ByteOrder.nativeOrder()).asIntBuffer();
        return new ForeignWindow.Wayland(
                handle("GLFWNativeWayland", "glfwGetWaylandDisplay"),
                handle("GLFWNativeWayland", "glfwGetWaylandWindow", glfw),
                () -> {
                    try {
                        query.invoke(null, glfw, width, height);
                        return ((long) width.get(0) << 32) | (height.get(0) & 0xffffffffL);
                    } catch (ReflectiveOperationException failure) {
                        throw new IllegalStateException("Cannot query GLFW Wayland drawable size", failure);
                    }
                });
    }

    private long handle(String binding, String query) throws ReflectiveOperationException {
        return (long) Class.forName("org.lwjgl.glfw." + binding, true, host)
                .getMethod(query)
                .invoke(null);
    }

    private long handle(String binding, String query, long window) throws ReflectiveOperationException {
        return (long) Class.forName("org.lwjgl.glfw." + binding, true, host)
                .getMethod(query, long.class)
                .invoke(null, window);
    }

    void restoreDispatch() throws ReflectiveOperationException {
        publish.invoke(null, capabilities);
    }
}
