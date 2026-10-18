package org.lwjglx.opengl;

/** Synthetic input/swap fixture, never touches a native window. */
public final class Display {
    public static int updates;
    private Display() { }
    public static void update(boolean processMessages) { updates++; }
}
