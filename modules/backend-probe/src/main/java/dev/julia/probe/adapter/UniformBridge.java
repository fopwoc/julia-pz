package dev.julia.probe.adapter;

import com.gtnewhorizons.angelica.glsm.GLStateManager;
import java.nio.IntBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

/** LWJGL 3 overloads missing from the published GLSM ABI. */
public final class UniformBridge {
    private UniformBridge() { }

    public static void glUniform1(int location, float[] values) {
        var buffer = MemoryUtil.memAllocFloat(values.length);
        try {
            buffer.put(values).flip();
            GLStateManager.glUniform1(location, buffer);
        } finally {
            MemoryUtil.memFree(buffer);
        }
    }

    public static void glUniformMatrix4(int location, boolean transpose, float[] values) {
        // LWJGL derives count by integer division; incomplete trailing matrices are ignored.
        int count = values.length / 16 * 16;
        var buffer = MemoryUtil.memAllocFloat(count);
        try {
            buffer.put(values, 0, count).flip();
            GLStateManager.glUniformMatrix4(location, transpose, buffer);
        } finally {
            MemoryUtil.memFree(buffer);
        }
    }

    public static String glGetActiveUniform(int program, int index, int maxLength, IntBuffer size, IntBuffer type) {
        if (maxLength < 0) throw new IllegalArgumentException("Negative uniform name capacity");
        if (!size.hasRemaining() || !type.hasRemaining() || size.isReadOnly() || type.isReadOnly()) {
            throw new IllegalArgumentException("Uniform size/type outputs must each have a writable element");
        }
        var name = MemoryUtil.memAlloc(Math.max(0, maxLength - 1));
        try (var stack = MemoryStack.stackPush()) {
            var length = stack.callocInt(1);
            var actualSize = stack.callocInt(1);
            var actualType = stack.callocInt(1);
            // The upstream backend writes absolute index zero. Scratch outputs preserve caller positions.
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
