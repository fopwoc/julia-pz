package org.lwjglx.opengl;

import org.lwjgl.opengl.GLCapabilities;

/** Synthetic input/swap fixture, never touches a native window. */
public final class Display {
    public static int updates;
    public static GLCapabilities capabilities;

    private Display() {}

    public static void update(boolean processMessages) {
        updates++;
    }
}
