package dev.fopwoc.julia.backend.bridge;

import com.gtnewhorizons.angelica.glsm.GLStateManager;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

/** Array overloads preserve GLSM's resource ownership and framebuffer state tracking. */
public final class ResourceArrayBridge {
    private ResourceArrayBridge() {}

    public static void glGenTextures(int[] names) {
        for (int i = 0; i < names.length; i++) names[i] = GLStateManager.glGenTextures();
    }

    public static void glDeleteTextures(int[] names) {
        for (int name : names) GLStateManager.glDeleteTextures(name);
    }

    public static void glDeleteFramebuffers(int[] names) {
        for (int name : names) GLStateManager.glDeleteFramebuffers(name);
    }

    public static void glGetIntegeri_v(int parameter, int index, int[] values) {
        try (var stack = MemoryStack.stackPush()) {
            var buffer = stack.mallocInt(values.length);
            GLStateManager.glGetIntegeri(parameter, index, buffer);
            buffer.get(0, values);
        }
    }

    public static void glGetAttachedShaders(int program, int[] count, int[] shaders) {
        try (var stack = MemoryStack.stackPush()) {
            var written = stack.callocInt(1);
            var names = stack.mallocInt(shaders.length);
            GLStateManager.glGetAttachedShaders(program, written, names);
            int total = Math.min(written.get(0), shaders.length);
            names.get(0, shaders, 0, total);
            if (count != null && count.length > 0) count[0] = total;
        }
    }

    public static void glDrawBuffers(int[] attachments) {
        var buffer = MemoryUtil.memAllocInt(attachments.length);
        try {
            buffer.put(attachments).flip();
            GLStateManager.glDrawBuffers(buffer);
        } finally {
            MemoryUtil.memFree(buffer);
        }
    }
}
