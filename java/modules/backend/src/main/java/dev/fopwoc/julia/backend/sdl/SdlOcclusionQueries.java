package dev.fopwoc.julia.backend.sdl;

import com.gtnewhorizons.angelica.sdlgpu.SDLGPUGate;
import dev.fopwoc.julia.backend.Upstream;
import dev.fopwoc.julia.backend.query.OcclusionDriver;
import dev.fopwoc.julia.backend.utils.Sdl;
import org.lwjgl.system.JNI;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

/**
 * Narrow binding to Julia's native visibility extension (Metal: visibility result buffers). Query boundaries end the open
 * render pass, so every counted draw lands in a pass that starts while the query is active.
 */
public final class SdlOcclusionQueries implements OcclusionDriver {
    private static final long BEGIN = SdlExtension.symbol("JULIA_SDL_BeginVisibilityQuery");
    private static final long END = SdlExtension.symbol("JULIA_SDL_EndVisibilityQuery");
    private static final long READ = SdlExtension.symbol("JULIA_SDL_ReadVisibilityQuery");
    private static final long RELEASE = SdlExtension.symbol("JULIA_SDL_ReleaseVisibilityQuery");

    public SdlOcclusionQueries() {
        SdlExtension.requireAbi("JULIA_SDL_VisibilityABI", 1);
    }

    @Override
    public long begin() {
        Upstream.commandBufferOutsidePasses();
        return Sdl.require(JNI.invokePP(SDLGPUGate.device().getDevice(), BEGIN), "Begin GPU visibility query");
    }

    @Override
    public void end(long query) {
        Upstream.commandBufferOutsidePasses();
        if (JNI.invokePI(query, END) != 0) throw Sdl.failure("End GPU visibility query");
    }

    @Override
    public boolean available(long query) {
        return read(query, false) >= 0;
    }

    @Override
    public long result(long query) {
        // A blocking GL result request must submit the counted passes before waiting for the GPU.
        long available = read(query, false);
        if (available >= 0) return available;
        Upstream.submitPendingCommands();
        long result = read(query, true);
        if (result < 0) throw new IllegalStateException("Blocking GPU visibility read remained unavailable");
        return result;
    }

    private static long read(long query, boolean wait) {
        if (query == 0) throw new IllegalArgumentException("Invalid GPU visibility query handle");
        try (var stack = MemoryStack.stackPush()) {
            var value = stack.mallocLong(1);
            int status = JNI.invokePPI(query, wait ? 1 : 0, MemoryUtil.memAddress(value), READ);
            if (status < 0) throw Sdl.failure("Read GPU visibility query");
            // Counts are unsigned; a negative long would collide with "unavailable".
            if (status != 0 && value.get(0) < 0) throw new IllegalStateException("GPU sample count exceeds 2^63");
            return status == 0 ? -1 : value.get(0);
        }
    }

    @Override
    public void release(long query) {
        if (query != 0) JNI.invokePV(query, RELEASE);
    }

    @Override
    public void close() {}
}
