package dev.fopwoc.julia.backend.runtime;

import static org.lwjgl.sdl.SDLGPU.SDL_GPU_TEXTUREFORMAT_R8G8B8A8_UNORM;
import static org.lwjgl.sdl.SDLGPU.SDL_WaitForGPUIdle;

import com.gtnewhorizons.angelica.sdlgpu.SDLGPUGate;
import com.gtnewhorizons.angelica.sdlgpu.frame.OffscreenTarget;
import com.gtnewhorizons.angelica.sdlgpu.resource.ResourceManager;
import dev.fopwoc.julia.backend.Upstream;
import dev.fopwoc.julia.backend.utils.Sdl;
import org.lwjglx.opengl.Display;

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
        var target = frames.finalTarget();
        var resources = Upstream.resources();
        // Upstream's ensureSize always asks the window's swapchain, which an offscreen renderer does not have.
        int format = format(target, resources);
        target.destroy(resources);
        target.create(SDLGPUGate.device(), resources, width, height, format);
        var state = Upstream.context();
        int draw = state.boundFboId, read = state.boundReadFboId;
        Upstream.renderBackend().invalidateStateMirror();
        state.boundFboId = draw;
        state.boundReadFboId = read;
    }

    /** A resize keeps the target's format; a new target matches the swapchain, or RGBA8 without a window. */
    private static int format(OffscreenTarget target, ResourceManager resources) {
        if (target.isReady()) return resources.getFbo(target.fboId()).colorFormats[0];
        return Display.getWindow() != 0
                ? SDLGPUGate.device().getSwapchainTextureFormat()
                : SDL_GPU_TEXTUREFORMAT_R8G8B8A8_UNORM;
    }
}
