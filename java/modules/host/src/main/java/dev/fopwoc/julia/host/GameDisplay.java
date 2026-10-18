package dev.fopwoc.julia.host;

import dev.fopwoc.julia.runtime.ForeignWindow;
import java.lang.reflect.Method;

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

    ForeignWindow window() throws ReflectiveOperationException {
        if (!System.getProperty("os.name").startsWith("Mac"))
            throw new UnsupportedOperationException("Live startup currently supports macOS only");
        long glfw = (long) Class.forName("org.lwjglx.opengl.Display", true, host)
                .getMethod("getWindow")
                .invoke(null);
        if (glfw == 0) throw new IllegalStateException("First graphics call arrived before PZ created its GLFW window");
        long cocoa = (long) Class.forName("org.lwjgl.glfw.GLFWNativeCocoa", true, host)
                .getMethod("glfwGetCocoaWindow", long.class)
                .invoke(null, glfw);
        return new ForeignWindow.Cocoa(cocoa);
    }

    void restoreDispatch() throws ReflectiveOperationException {
        publish.invoke(null, capabilities);
    }
}
