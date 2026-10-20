package dev.fopwoc.julia.backend.bridge;

import com.gtnewhorizons.angelica.glsm.backend.BackendManager;
import dev.fopwoc.julia.backend.BackendInternals;
import java.nio.IntBuffer;

/** Named operations address explicit objects and preserve both logical framebuffer bindings. */
public final class FramebufferBridge {
    private FramebufferBridge() {}

    public static int glCreateFramebuffers() {
        return BackendManager.RENDER_BACKEND.createFramebuffers();
    }

    public static void glCreateFramebuffers(int[] names) {
        for (int i = 0; i < names.length; i++) names[i] = glCreateFramebuffers();
    }

    public static void glCreateFramebuffers(IntBuffer names) {
        for (int i = names.position(); i < names.limit(); i++) names.put(i, glCreateFramebuffers());
    }

    public static void glNamedFramebufferTexture(int fbo, int attachment, int texture, int level) {
        BackendInternals.framebuffers().attach(fbo, attachment, texture, level, 0, false);
    }

    public static void glNamedFramebufferTextureLayer(int fbo, int attachment, int texture, int level, int layer) {
        BackendInternals.framebuffers().attach(fbo, attachment, texture, level, layer, true);
    }

    public static void glFramebufferTextureLayer(int target, int attachment, int texture, int level, int layer) {
        glNamedFramebufferTextureLayer(
                BackendInternals.framebuffers().selected(target), attachment, texture, level, layer);
    }

    public static void glNamedFramebufferDrawBuffer(int fbo, int buffer) {
        BackendInternals.framebuffers().drawBuffers(fbo, new int[] {buffer});
    }

    public static void glNamedFramebufferDrawBuffers(int fbo, int[] buffers) {
        BackendInternals.framebuffers().drawBuffers(fbo, buffers);
    }

    public static void glNamedFramebufferDrawBuffers(int fbo, IntBuffer buffers) {
        int[] values = new int[buffers.remaining()];
        for (int i = 0; i < values.length; i++) values[i] = buffers.get(buffers.position() + i);
        glNamedFramebufferDrawBuffers(fbo, values);
    }

    public static void glNamedFramebufferReadBuffer(int fbo, int buffer) {
        BackendInternals.framebuffers().readBuffer(fbo, buffer);
    }

    public static int glCheckNamedFramebufferStatus(int fbo, int target) {
        return BackendInternals.framebuffers().status(fbo, target);
    }
}
