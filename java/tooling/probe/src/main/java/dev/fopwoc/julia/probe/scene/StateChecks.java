package dev.fopwoc.julia.probe.scene;

import static org.lwjgl.opengl.GL11.GL_DEPTH_CLEAR_VALUE;
import static org.lwjgl.opengl.GL11.GL_RGBA;
import static org.lwjgl.opengl.GL11.GL_RGBA8;
import static org.lwjgl.opengl.GL11.GL_TEXTURE_HEIGHT;
import static org.lwjgl.opengl.GL11.GL_TEXTURE_WIDTH;
import static org.lwjgl.opengl.GL11.GL_UNSIGNED_BYTE;
import static org.lwjgl.opengl.GL11.glBindTexture;
import static org.lwjgl.opengl.GL11.glDeleteTextures;
import static org.lwjgl.opengl.GL11.glGenTextures;
import static org.lwjgl.opengl.GL11.glGetDouble;
import static org.lwjgl.opengl.GL11.glGetFloat;
import static org.lwjgl.opengl.GL11.glGetInteger;
import static org.lwjgl.opengl.GL11.glGetIntegerv;
import static org.lwjgl.opengl.GL11.glGetTexLevelParameteri;
import static org.lwjgl.opengl.GL15.glBindBuffer;
import static org.lwjgl.opengl.GL15.glDeleteBuffers;
import static org.lwjgl.opengl.GL15.glGenBuffers;
import static org.lwjgl.opengl.GL21.GL_PIXEL_UNPACK_BUFFER;
import static org.lwjgl.opengl.GL30.GL_TEXTURE_2D_ARRAY;
import static org.lwjgl.opengl.GL31.GL_MAX_TEXTURE_BUFFER_SIZE;

import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL43;
import org.lwjgl.system.MemoryStack;

/** Capability snapshot, scalar aliases, and storage-only allocation through the redirected API. */
final class StateChecks {
    private StateChecks() {}

    static void run() {
        capabilities();
        unsupportedQueries();
        if (glGetInteger(GL_MAX_TEXTURE_BUFFER_SIZE) <= 0)
            throw new IllegalStateException("Buffer texture limit is unavailable");
        depthAlias();
        arrayAllocation();
    }

    private static void unsupportedQueries() {
        for (boolean buffered : new boolean[] {false, true}) {
            try (var stack = MemoryStack.stackPush()) {
                boolean rejected = false;
                try {
                    if (buffered) glGetIntegerv(Integer.MAX_VALUE, stack.mallocInt(1));
                    else glGetInteger(Integer.MAX_VALUE);
                } catch (UnsupportedOperationException expected) {
                    rejected = expected.getMessage().contains("pname=0x7fffffff");
                }
                if (!rejected)
                    throw new IllegalStateException("Unknown integer state query returned a fabricated value");
            }
        }
        boolean rejected = false;
        try {
            glGetFloat(0x7fffffff);
        } catch (UnsupportedOperationException expected) {
            rejected = expected.getMessage().contains("pname=0x7fffffff");
        }
        if (!rejected)
            throw new IllegalStateException("Unknown floating-point state query returned a fabricated value");
    }

    /** The backend's advertised snapshot, including a vendor flag it must not claim. */
    private static void capabilities() {
        var capabilities = GL.getCapabilities();
        if (!capabilities.OpenGL33 || !capabilities.GL_ARB_framebuffer_object || capabilities.GL_NVX_gpu_memory_info) {
            throw new IllegalStateException("Backend capability snapshot mismatch");
        }
    }

    /** {@code glClearDepthf} widens to the double-precision GLSM entry point. */
    private static void depthAlias() {
        GL43.glClearDepthf(0.25f);
        if (glGetDouble(GL_DEPTH_CLEAR_VALUE) != 0.25) throw new IllegalStateException("Depth conversion mismatch");
        GL43.glClearDepthf(1.0f);
    }

    /** A literal null pointer allocates storage; offset zero into an unpack buffer must not be mistaken for it. */
    private static void arrayAllocation() {
        int texture = glGenTextures();
        int buffer = glGenBuffers();
        try {
            glBindTexture(GL_TEXTURE_2D_ARRAY, texture);
            GL12.glTexImage3D(GL_TEXTURE_2D_ARRAY, 0, GL_RGBA8, 8, 4, 2, 0, GL_RGBA, GL_UNSIGNED_BYTE, 0L);
            if (glGetTexLevelParameteri(GL_TEXTURE_2D_ARRAY, 0, GL_TEXTURE_WIDTH) != 8
                    || glGetTexLevelParameteri(GL_TEXTURE_2D_ARRAY, 0, GL_TEXTURE_HEIGHT) != 4) {
                throw new IllegalStateException("Null-pointer array allocation dimensions mismatch");
            }
            glBindBuffer(GL_PIXEL_UNPACK_BUFFER, buffer);
            boolean rejected = false;
            try {
                GL12.glTexImage3D(GL_TEXTURE_2D_ARRAY, 0, GL_RGBA8, 8, 4, 2, 0, GL_RGBA, GL_UNSIGNED_BYTE, 0L);
            } catch (UnsupportedOperationException expected) {
                rejected = true;
            }
            if (!rejected) throw new IllegalStateException("PBO offset zero was mistaken for null client storage");
        } finally {
            glBindBuffer(GL_PIXEL_UNPACK_BUFFER, 0);
            glBindTexture(GL_TEXTURE_2D_ARRAY, 0);
            glDeleteBuffers(buffer);
            glDeleteTextures(texture);
        }
    }
}
