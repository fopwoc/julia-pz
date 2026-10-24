package dev.fopwoc.julia.backend.bridge;

import static org.lwjgl.opengl.GL31.GL_UNIFORM_BUFFER_SIZE;
import static org.lwjgl.opengl.GL31.GL_UNIFORM_BUFFER_START;
import static org.lwjgl.opengl.GL43.GL_SHADER_STORAGE_BUFFER_SIZE;
import static org.lwjgl.opengl.GL43.GL_SHADER_STORAGE_BUFFER_START;

import com.gtnewhorizons.angelica.sdlgpu.frame.ContextState;
import dev.fopwoc.julia.backend.Upstream;

/** Indexed buffer-binding ranges, as callers save and restore them around their own bindings. */
public final class BufferBindingBridge {
    private BufferBindingBridge() {}

    public static long glGetInteger64i(int parameter, int index) {
        if (index < 0 || index >= ContextState.MAX_INDEXED_BUFFERS)
            throw new IllegalArgumentException("Indexed buffer binding out of range: " + index);
        var state = Upstream.context();
        return switch (parameter) {
            case GL_UNIFORM_BUFFER_START -> state.uboRangeOffset[index];
            case GL_UNIFORM_BUFFER_SIZE -> state.uboRangeSize[index];
            // Storage buffers always bind whole (BufferLayer rejects offsets), which GL reports as a zero range.
            case GL_SHADER_STORAGE_BUFFER_START, GL_SHADER_STORAGE_BUFFER_SIZE -> 0;
            default -> throw new UnsupportedOperationException("Indexed 64-bit query not implemented: " + parameter);
        };
    }
}
