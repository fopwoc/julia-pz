package dev.julia.backend.bridge;

import dev.julia.backend.BackendInternals;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;

/** Queries actual hosted-backend values for ordinary uniforms; outputs preserve their positions. */
public final class UniformQueryBridge {
    private UniformQueryBridge() {}

    public static float glGetUniformf(int program, int location) {
        return read(program, location).scalarFloat();
    }

    public static int glGetUniformi(int program, int location) {
        return read(program, location).scalarInt();
    }

    public static void glGetUniformfv(int program, int location, FloatBuffer values) {
        read(program, location).write(values);
    }

    public static void glGetUniformfv(int program, int location, float[] values) {
        read(program, location).write(values);
    }

    public static void glGetUniformiv(int program, int location, IntBuffer values) {
        read(program, location).write(values);
    }

    public static void glGetUniformiv(int program, int location, int[] values) {
        read(program, location).write(values);
    }

    private static UniformValue read(int program, int location) {
        var object = BackendInternals.shaders().getProgram(program);
        if (object == null || !object.linked) throw new IllegalArgumentException("Unknown or unlinked program: " + program);
        if (object.externalUboBinding >= 0) {
            throw new UnsupportedOperationException("External uniform-buffer state is not an ordinary uniform query");
        }
        return UniformState.read(object, BackendInternals.context().uniformStaging(object), location);
    }
}
