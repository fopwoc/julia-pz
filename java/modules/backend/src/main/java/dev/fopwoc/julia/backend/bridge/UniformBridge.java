package dev.fopwoc.julia.backend.bridge;

import static org.lwjgl.opengl.GL20.GL_CURRENT_PROGRAM;

import com.gtnewhorizons.angelica.glsm.GLStateManager;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

/** LWJGL 3 overloads missing from the published GLSM ABI. */
public final class UniformBridge {
    private UniformBridge() {}

    // Upstream copies uniform data before returning, so uploads this small use the thread stack instead of malloc.
    private static final int STACK_FLOATS = 1024;

    public static void glUniform1(int location, float[] values) {
        try (var stack = MemoryStack.stackPush()) {
            var buffer = stage(stack, values, values.length);
            try {
                GLStateManager.glUniform1(location, buffer);
            } finally {
                release(buffer);
            }
        }
    }

    public static void glUniformMatrix3(int location, boolean transpose, float[] values) {
        // LWJGL derives count by integer division; incomplete trailing matrices are ignored.
        try (var stack = MemoryStack.stackPush()) {
            var buffer = stage(stack, values, values.length / 9 * 9);
            try {
                GLStateManager.glUniformMatrix3(location, transpose, buffer);
            } finally {
                release(buffer);
            }
        }
    }

    public static void glUniformMatrix4(int location, boolean transpose, float[] values) {
        // LWJGL derives count by integer division; incomplete trailing matrices are ignored.
        try (var stack = MemoryStack.stackPush()) {
            var buffer = stage(stack, values, values.length / 16 * 16);
            try {
                GLStateManager.glUniformMatrix4(location, transpose, buffer);
            } finally {
                release(buffer);
            }
        }
    }

    // Program uniforms update a program without binding it; GL keeps the caller's current program.
    public static void glProgramUniform1f(int program, int location, float x) {
        int previous = use(program);
        try {
            GLStateManager.glUniform1f(location, x);
        } finally {
            restore(program, previous);
        }
    }

    public static void glProgramUniform2f(int program, int location, float x, float y) {
        int previous = use(program);
        try {
            GLStateManager.glUniform2f(location, x, y);
        } finally {
            restore(program, previous);
        }
    }

    public static void glProgramUniform4f(int program, int location, float x, float y, float z, float w) {
        int previous = use(program);
        try {
            GLStateManager.glUniform4f(location, x, y, z, w);
        } finally {
            restore(program, previous);
        }
    }

    public static void glProgramUniform1i(int program, int location, int x) {
        int previous = use(program);
        try {
            GLStateManager.glUniform1i(location, x);
        } finally {
            restore(program, previous);
        }
    }

    public static void glProgramUniform4fv(int program, int location, float[] values) {
        int previous = use(program);
        try {
            GLStateManager.glUniform4(location, values);
        } finally {
            restore(program, previous);
        }
    }

    private static int use(int program) {
        if (program == 0) throw new IllegalArgumentException("Program uniform target must be a program");
        // GLSM's binding is what the caller observes and what it restores against.
        int previous = GLStateManager.glGetInteger(GL_CURRENT_PROGRAM);
        if (previous != program) GLStateManager.glUseProgram(program);
        return previous;
    }

    private static void restore(int program, int previous) {
        if (previous != program) GLStateManager.glUseProgram(previous);
    }

    private static FloatBuffer stage(MemoryStack stack, float[] values, int count) {
        var buffer = count <= STACK_FLOATS ? stack.mallocFloat(count) : MemoryUtil.memAllocFloat(count);
        return buffer.put(values, 0, count).flip();
    }

    private static void release(FloatBuffer buffer) {
        if (buffer.capacity() > STACK_FLOATS) MemoryUtil.memFree(buffer);
    }

    public static String glGetActiveUniform(int program, int index, int maxLength, IntBuffer size, IntBuffer type) {
        if (maxLength < 0) throw new IllegalArgumentException("Negative uniform name capacity");
        if (!size.hasRemaining() || !type.hasRemaining() || size.isReadOnly() || type.isReadOnly()) {
            throw new IllegalArgumentException("Uniform size/type outputs must each have a writable element");
        }
        var name = MemoryUtil.memAlloc(maxLength);
        try (var stack = MemoryStack.stackPush()) {
            var length = stack.callocInt(1);
            var actualSize = stack.callocInt(1);
            var actualType = stack.callocInt(1);
            // Scratch outputs isolate the caller's positions; capacity includes the null terminator.
            GLStateManager.glGetActiveUniform(program, index, length, actualSize, actualType, name);
            size.put(size.position(), actualSize.get(0));
            type.put(type.position(), actualType.get(0));
            name.position(0);
            return MemoryUtil.memUTF8(name, length.get(0));
        } finally {
            MemoryUtil.memFree(name);
        }
    }
}
