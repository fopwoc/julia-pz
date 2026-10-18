package dev.julia.zomboid;

import java.lang.reflect.Method;

/** Restores the host's native GL dispatch table, which is shared across class loaders on the same thread. */
final class HostGlState implements AutoCloseable {
    // Bound via reflection: the mod compiles without LWJGL and must use the game's own binding classes.
    private static final int GL_TEXTURE_BINDING_2D = 0x8069;

    private final Thread owner = Thread.currentThread();
    private final Object capabilities;
    private final Method getCapabilities;
    private final Method setCapabilities;
    private final Method getInteger;
    private final int textureBinding;
    private boolean restored;

    private HostGlState(Object capabilities, Method getCapabilities, Method setCapabilities, Method getInteger, int textureBinding) {
        this.capabilities = capabilities;
        this.getCapabilities = getCapabilities;
        this.setCapabilities = setCapabilities;
        this.getInteger = getInteger;
        this.textureBinding = textureBinding;
    }

    static HostGlState capture(ClassLoader host) throws ReflectiveOperationException {
        Class<?> gl = Class.forName("org.lwjgl.opengl.GL", true, host);
        Method get = gl.getMethod("getCapabilities");
        Object original = get.invoke(null);
        if (original == null) throw new IllegalStateException("The game render thread has no GL capabilities");
        Method query = Class.forName("org.lwjgl.opengl.GL11", true, host).getMethod("glGetInteger", int.class);
        int binding = (int) query.invoke(null, GL_TEXTURE_BINDING_2D);
        return new HostGlState(original, get, gl.getMethod("setCapabilities", get.getReturnType()), query, binding);
    }

    void restoreAndVerify() throws ReflectiveOperationException {
        if (Thread.currentThread() != owner) throw new IllegalStateException("GL capabilities belong to " + owner.getName());
        if (restored) return;
        // Calling the host setter is essential even when its Java ThreadLocal still contains this object.
        // LWJGL's fast native dispatch reads JNIEnv reserved state changed by the isolated runtime.
        setCapabilities.invoke(null, capabilities);
        if (getCapabilities.invoke(null) != capabilities) throw new IllegalStateException("Host GL capability identity changed");
        int binding = (int) getInteger.invoke(null, GL_TEXTURE_BINDING_2D);
        if (binding != textureBinding) throw new IllegalStateException("Host texture binding changed across the probe");
        restored = true;
    }

    @Override
    public void close() throws ReflectiveOperationException {
        restoreAndVerify();
    }
}
