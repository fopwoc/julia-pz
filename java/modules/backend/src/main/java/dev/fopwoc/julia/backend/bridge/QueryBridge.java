package dev.fopwoc.julia.backend.bridge;

import com.gtnewhorizons.angelica.glsm.GLStateManager;

/** LWJGL array and signed query overloads backed by the hosted GPU timer implementation. */
public final class QueryBridge {
    private QueryBridge() {}

    public static void glGenQueries(int[] names) {
        for (int i = 0; i < names.length; i++) names[i] = GLStateManager.glGenQueries();
    }

    public static void glDeleteQueries(int[] names) {
        for (int name : names) GLStateManager.glDeleteQueries(name);
    }

    public static long glGetQueryObjecti64(int name, int parameter) {
        return GLStateManager.glGetQueryObjectui64(name, parameter);
    }
}
