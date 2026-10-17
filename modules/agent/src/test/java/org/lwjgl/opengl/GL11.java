package org.lwjgl.opengl;

/** Test binding; the agent smoke test must never load a real native graphics library. */
public final class GL11 {
    private GL11() { }

    public static int glGetError() {
        return 42;
    }
}
