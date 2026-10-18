package dev.julia.probe.host;

import org.lwjgl.opengl.GL;

/** Releases the probe's fabricated dispatch and missing-function table before restoring the game runtime. */
public final class ProbeGlRuntime {
    private ProbeGlRuntime() { }

    public static void close() {
        try {
            GL.setCapabilities(null);
        } finally {
            GL.destroy();
        }
    }
}
