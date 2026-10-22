package dev.fopwoc.julia.backend.runtime;

import static org.lwjgl.sdl.SDLGPU.SDL_WaitForGPUIdle;

import com.gtnewhorizons.angelica.sdlgpu.SDLGPUGate;
import dev.fopwoc.julia.backend.Upstream;
import dev.fopwoc.julia.backend.utils.Sdl;

/** Changes the logical default framebuffer without changing the borrowed native window. */
public final class DefaultTarget {
    private DefaultTarget() {}

    public static void resize(int width, int height) {
        if (width <= 0 || height <= 0) throw new IllegalArgumentException("Positive render dimensions are required");
        var frames = Upstream.frames();
        if (frames.getFbo0Width() == width && frames.getFbo0Height() == height) return;
        // Asset uploads can already own a command buffer before the first sized game frame begins.
        Upstream.submitPendingCommands();
        Sdl.require(SDL_WaitForGPUIdle(SDLGPUGate.device().getDevice()), "Drain resized default framebuffer");
        frames.finalTarget().ensureSize(SDLGPUGate.device(), Upstream.resources(), width, height);
        var state = Upstream.context();
        int draw = state.boundFboId, read = state.boundReadFboId;
        Upstream.renderBackend().invalidateStateMirror();
        state.boundFboId = draw;
        state.boundReadFboId = read;
    }
}
