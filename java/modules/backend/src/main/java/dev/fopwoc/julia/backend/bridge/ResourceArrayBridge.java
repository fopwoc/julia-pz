package dev.fopwoc.julia.backend.bridge;

import com.gtnewhorizons.angelica.glsm.GLStateManager;
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
