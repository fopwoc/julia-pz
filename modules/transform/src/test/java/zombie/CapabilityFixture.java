package zombie;

import org.lwjgl.opengl.GL;

/** Local capability objects survive branches through transformed stack-map frames. */
public final class CapabilityFixture {
    private CapabilityFixture() {}

    public static boolean displayFlag() {
        return org.lwjglx.opengl.Display.capabilities.GL_ARB_texture_compression;
    }

    public static int flags(boolean branch) {
        var capabilities = GL.getCapabilities();
        int result = branch ? 1 : 0;
        if (capabilities.OpenGL33) result += 2;
        if (capabilities.OpenGL43) result += 4;
        if (capabilities.GL_ARB_framebuffer_object) result += 8;
        if (capabilities.GL_NVX_gpu_memory_info) result += 16;
        return result;
    }
}
