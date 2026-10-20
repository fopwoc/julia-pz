package dev.fopwoc.julia.backend.sdl;

import com.gtnewhorizons.angelica.sdlgpu.SDLGPUGate;
import dev.fopwoc.julia.backend.Upstream;
import dev.fopwoc.julia.backend.query.TimestampDriver;
import dev.fopwoc.julia.backend.utils.Sdl;
import org.lwjgl.system.JNI;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

/** Narrow binding to Julia's native GPU timestamp extension (Metal: counter sample buffers). */
public final class SdlTimestamps implements TimestampDriver {
    private static final long CREATE = SdlExtension.symbol("JULIA_SDL_CreateTimestampClock");
    private static final long RECORD = SdlExtension.symbol("JULIA_SDL_RecordTimestamp");
    private static final long READ = SdlExtension.symbol("JULIA_SDL_ReadTimestamp");
    private static final long RELEASE = SdlExtension.symbol("JULIA_SDL_ReleaseTimestamp");
    private static final long CLOSE = SdlExtension.symbol("JULIA_SDL_ReleaseTimestampClock");
    private long clock;

    public SdlTimestamps() {
        SdlExtension.requireAbi("JULIA_SDL_TimestampABI", 1);
        clock = Sdl.require(JNI.invokePP(SDLGPUGate.device().getDevice(), CREATE), "Create GPU timestamp clock");
    }

    @Override
    public long record() {
        if (clock == 0) throw new IllegalStateException("Timestamp clock is closed");
        return Sdl.require(JNI.invokePPP(clock, Upstream.commandBufferOutsidePasses(), RECORD), "Record GPU timestamp");
    }

    @Override
    public boolean available(long sample) {
        return read(sample, false) >= 0;
    }

    @Override
    public long result(long sample) {
        // A blocking GL result request must submit recorded markers before waiting for the GPU.
        long available = read(sample, false);
        if (available >= 0) return available;
        Upstream.submitPendingCommands();
        long result = read(sample, true);
        if (result < 0) throw new IllegalStateException("Blocking GPU timestamp read remained unavailable");
        return result;
    }

    private static long read(long sample, boolean wait) {
        if (sample == 0) throw new IllegalArgumentException("Invalid GPU timestamp handle");
        try (var stack = MemoryStack.stackPush()) {
            var value = stack.mallocLong(1);
            int status = JNI.invokePPI(sample, wait ? 1 : 0, MemoryUtil.memAddress(value), READ);
            if (status < 0) throw Sdl.failure("Read GPU timestamp");
            return status == 0 ? -1 : value.get(0);
        }
    }

    @Override
    public void release(long sample) {
        if (sample != 0) JNI.invokePV(sample, RELEASE);
    }

    @Override
    public void close() {
        if (clock != 0) {
            JNI.invokePV(clock, CLOSE);
            clock = 0;
        }
    }
}
