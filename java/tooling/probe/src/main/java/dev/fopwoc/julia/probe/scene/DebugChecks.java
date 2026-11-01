package dev.fopwoc.julia.probe.scene;

import static org.lwjgl.opengl.GL43.GL_DEBUG_SEVERITY_HIGH;
import static org.lwjgl.opengl.GL43.GL_DEBUG_SOURCE_API;
import static org.lwjgl.opengl.GL43.GL_DEBUG_TYPE_ERROR;

import dev.fopwoc.julia.probe.adapter.DebugMessages;
import org.lwjgl.opengl.GL43;
import org.lwjgl.system.MemoryUtil;

/** Exercises redirected registration, relay delivery, replacement and unregistration. */
final class DebugChecks {
    private DebugChecks() {}

    static void run() {
        String[] received = new String[1];
        int[] calls = new int[2];
        long parameter = 0x123456789L;
        try {
            GL43.glDebugMessageCallback(
                    (source, type, id, severity, length, message, pointer) -> {
                        if (source != GL_DEBUG_SOURCE_API
                                || type != GL_DEBUG_TYPE_ERROR
                                || severity != GL_DEBUG_SEVERITY_HIGH
                                || id != 0
                                || pointer != parameter) throw new IllegalStateException("Debug arguments changed");
                        calls[0]++;
                        received[0] = MemoryUtil.memUTF8(message, length);
                    },
                    parameter);
            DebugMessages.emit("Julia callback \u2713");
            if (calls[0] != 1 || !"Julia callback \u2713".equals(received[0])) {
                throw new IllegalStateException("Debug callback delivery failed");
            }
            GL43.glDebugMessageCallback(
                    (s, t, i, v, l, m, p) -> {
                        if (p != -0x23456789aL) throw new IllegalStateException("Replacement user pointer changed");
                        calls[1]++;
                    },
                    -0x23456789aL);
            DebugMessages.emit("replacement");
            GL43.glDebugMessageCallback(null, 0L);
            DebugMessages.emit("unregistered");
            if (calls[0] != 1 || calls[1] != 1) throw new IllegalStateException("Debug callback lifecycle failed");
        } finally {
            GL43.glDebugMessageCallback(null, 0L);
        }
    }
}
