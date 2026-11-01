package dev.fopwoc.julia.backend.bridge;

import com.gtnewhorizons.angelica.glsm.GLSMQueries;
import com.gtnewhorizons.angelica.sdlgpu.Subsystems;

/** LWJGL array and signed query overloads backed by the hosted GPU timer implementation. */
public final class QueryBridge {
    private QueryBridge() {}

    public static void glGenQueries(int[] names) {
        for (int i = 0; i < names.length; i++) names[i] = GLSMQueries.glGenQueries();
    }

    public static void glDeleteQueries(int[] names) {
        for (int name : names) GLSMQueries.glDeleteQueries(name);
    }

    public static int glGetQueryi(int target, int parameter) {
        return Subsystems.current().queries().parameter(target, parameter);
    }

    public static long glGetQueryObjecti64(int name, int parameter) {
        return GLSMQueries.glGetQueryObjectui64(name, parameter);
    }
}
