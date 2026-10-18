package dev.julia.probe.scene;

import static org.lwjgl.opengl.GL11.GL_FLOAT;
import static org.lwjgl.opengl.GL20.GL_ACTIVE_UNIFORMS;
import static org.lwjgl.opengl.GL20.GL_FLOAT_MAT4;
import static org.lwjgl.opengl.GL20.glGetActiveUniform;
import static org.lwjgl.opengl.GL20.glGetProgrami;
import static org.lwjgl.opengl.GL20.glGetUniformLocation;
import static org.lwjgl.opengl.GL20.glUniform1fv;
import static org.lwjgl.opengl.GL20.glUniform1i;
import static org.lwjgl.opengl.GL20.glUniformMatrix4fv;
import static org.lwjgl.opengl.GL20.glUseProgram;

import org.lwjgl.opengl.GL43;
import org.lwjgl.system.MemoryStack;

/**
 * Uploads the adapter shader's uniforms through every bridged overload, then verifies reflection and read-back.
 *
 * <p>The uploaded values are chosen so that the draw only matches the expected image if every upload landed.
 */
final class UniformChecks {
    private UniformChecks() {}

    static void run(int program) {
        upload(program);
        verifyReflection(program);
        verifyValues(program);
    }

    private static void upload(int program) {
        int sampler = location(program, "image");
        if (sampler < 0) throw new IllegalStateException("Sampler uniform is missing");
        glUniform1i(sampler, 0);
        glUniform1fv(location(program, "weights"), new float[] {0.25f, 0.75f});
        // Transposed upload; the three trailing floats are an incomplete matrix LWJGL must ignore.
        glUniformMatrix4fv(location(program, "transform"), true, new float[] {
            1, 0, 0, 0.25f, 0, 1, 0, -0.5f, 0, 0, 1, 0, 0, 0, 0, 1, 99, 98, 97
        });
        try (var stack = MemoryStack.stackPush()) {
            var shift = stack.floats(99.0f, 0.25f, 0.5f);
            shift.position(1);
            GL43.glUniform2fv(location(program, "shift"), shift);
        }
        GL43.glUniform1iv(location(program, "channels"), new int[] {3, 5});
        GL43.glUniform1iv(location(program, "enabled"), new int[] {7});
        GL43.glUniform1iv(sampler, new int[] {0});
    }

    /** Array/matrix reflection, name truncation, and untouched output-buffer positions. */
    private static void verifyReflection(int program) {
        try (var stack = MemoryStack.stackPush()) {
            var size = stack.callocInt(3);
            var type = stack.callocInt(3);
            size.put(0, -77).position(1);
            type.put(0, -88).position(1);
            boolean weights = false;
            boolean transform = false;
            int count = glGetProgrami(program, GL_ACTIVE_UNIFORMS);
            for (int index = 0; index < count; index++) {
                String name = glGetActiveUniform(program, index, 128, size, type);
                if (name.equals("weights") || name.equals("weights[0]")) {
                    weights = size.get(1) == 2 && type.get(1) == GL_FLOAT;
                }
                if (name.equals("transform")) transform = size.get(1) == 1 && type.get(1) == GL_FLOAT_MAT4;
                if (!glGetActiveUniform(program, index, 0, size, type).isEmpty()
                        || !glGetActiveUniform(program, index, 1, size, type).isEmpty()) {
                    throw new IllegalStateException("Zero-capacity uniform names must be empty");
                }
                String truncated = glGetActiveUniform(program, index, 4, size, type);
                if (!truncated.equals(name.substring(0, Math.min(3, name.length())))) {
                    throw new IllegalStateException("Uniform name truncation mismatch: " + truncated);
                }
            }
            if (!weights
                    || !transform
                    || size.position() != 1
                    || type.position() != 1
                    || size.get(0) != -77
                    || type.get(0) != -88) {
                throw new IllegalStateException("Uniform reflection or output buffer positions are incorrect");
            }
        }
    }

    /** Tracked values read back with the program unbound, in every query shape. */
    private static void verifyValues(int program) {
        int sampler = location(program, "image");
        int channels = location(program, "channels");
        glUseProgram(0);
        try (var stack = MemoryStack.stackPush()) {
            if (GL43.glGetUniformi(program, sampler) != 0
                    || GL43.glGetUniformi(program, location(program, "enabled")) != 1
                    || GL43.glGetUniformf(program, channels) != 3.0f) {
                throw new IllegalStateException("Sampler/integer/boolean uniform state mismatch");
            }
            var vector = stack.floats(99, 88, 77, 66);
            vector.position(1);
            GL43.glGetUniformfv(program, location(program, "shift"), vector);
            if (vector.position() != 1
                    || vector.get(0) != 99
                    || vector.get(1) != 0.25f
                    || vector.get(2) != 0.5f
                    || vector.get(3) != 66) {
                throw new IllegalStateException("Uniform vector query output mismatch");
            }
            float[] weights = {-99, -88};
            GL43.glGetUniformfv(program, location(program, "weights"), weights);
            if (weights[0] != 0.25f || weights[1] != -88)
                throw new IllegalStateException("Array query must return one element");
            int[] integers = {-99, -88};
            GL43.glGetUniformiv(program, channels, integers);
            if (integers[0] != 3 || integers[1] != -88) throw new IllegalStateException("Integer array query mismatch");
            float[] matrix = new float[16];
            GL43.glGetUniformfv(program, location(program, "transform"), matrix);
            if (matrix[0] != 1
                    || matrix[5] != 1
                    || matrix[10] != 1
                    || matrix[15] != 1
                    || matrix[12] != 0.25f
                    || matrix[13] != -0.5f) {
                throw new IllegalStateException("Matrix query is not column-major");
            }
        } finally {
            glUseProgram(program);
        }
    }

    private static int location(int program, String name) {
        return glGetUniformLocation(program, name);
    }
}
