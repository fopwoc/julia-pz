package dev.fopwoc.julia.probe.scene;

import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL30.GL_FRAMEBUFFER;
import static org.lwjgl.opengl.GL30.glBindFramebuffer;

import com.gtnewhorizons.angelica.glsm.backend.BackendManager;
import com.gtnewhorizons.angelica.sdlgpu.SDLGPUGate;
import dev.fopwoc.julia.backend.BackendInternals;
import dev.fopwoc.julia.backend.runtime.InitialFrame;
import dev.fopwoc.julia.probe.adapter.ResourceReadback;

/** A startup frame must be black and remain valid while subsequent frames contain no draws. */
final class InitialFrameChecks {
    private InitialFrameChecks() {}

    static void run() {
        var backend = BackendManager.RENDER_BACKEND;
        var frames = BackendInternals.frames();
        var resources = BackendInternals.resources();
        var target = frames.finalTarget();
        boolean owned = !target.isReady();
        if (owned) target.create(SDLGPUGate.device(), resources, 8, 8, resources.mapTextureFormat(GL_RGBA8));
        try {
            glBindFramebuffer(GL_FRAMEBUFFER, 0);
            glClearColor(0.25f, 0.5f, 0.75f, 0.125f);
            InitialFrame.present(backend);
            if (frames.isFrameActive() || !target.hasContent())
                throw new IllegalStateException("Initial frame was not completed with defined contents");
            var state = BackendInternals.context();
            if (state.clearR != 0.25f || state.clearG != 0.5f || state.clearB != 0.75f || state.clearA != 0.125f)
                throw new IllegalStateException("Initial frame changed the game's clear color");
            for (int frame = 0; frame < 2; frame++) {
                backend.onFrameBegin();
                int texture = resources.getFbo(target.fboId()).colorGlIds[0];
                var pixels = ResourceReadback.layer(texture, 0);
                for (int i = 0; i < pixels.length; i++) {
                    int expected = i % 4 == 3 ? 255 : 0;
                    if ((pixels[i] & 255) != expected)
                        throw new IllegalStateException("Initial frame byte " + i + " got " + (pixels[i] & 255));
                }
                if (frame == 0) backend.onFrameEnd();
            }
        } finally {
            glClearColor(0, 0, 0, 0);
            if (owned) frames.destroyFinalTarget();
        }
    }
}
