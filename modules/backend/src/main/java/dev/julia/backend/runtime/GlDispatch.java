package dev.julia.backend.runtime;

import org.lwjgl.opengl.GL;

/** Releases the isolated runtime's fabricated GL dispatch and function provider before the host resumes. */
public final class GlDispatch {
    private GlDispatch() {}

    public static void release() {
        try {
            GL.setCapabilities(null);
        } finally {
            GL.destroy();
        }
    }
}
