package dev.fopwoc.julia.backend.query.macos;

import com.gtnewhorizons.angelica.sdlgpu.SDLGPUGate;
import dev.fopwoc.julia.backend.BackendInternals;
import dev.fopwoc.julia.backend.query.TimestampDriver;
import dev.fopwoc.julia.backend.utils.Sdl;
import org.lwjgl.sdl.SDL;
import org.lwjgl.system.JNI;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

/** Narrow binding to the versioned timestamp extension compiled inside Julia's SDL Metal backend. */
public final class MetalTimestamps implements TimestampDriver {
    private static final long CREATE = symbol("JULIA_SDL_CreateTimestampClock");
    private static final long RECORD = symbol("JULIA_SDL_RecordTimestamp");
    private static final long READ = symbol("JULIA_SDL_ReadTimestamp");
    private static final long RELEASE = symbol("JULIA_SDL_ReleaseTimestamp");
    private static final long CLOSE = symbol("JULIA_SDL_ReleaseTimestampClock");
    private long clock;

    public MetalTimestamps() {
        if (JNI.invokeI(symbol("JULIA_SDL_TimestampABI")) != 1)
            throw new IllegalStateException("Unexpected native GPU timestamp ABI");
        clock = Sdl.require(JNI.invokePP(SDLGPUGate.device().getDevice(), CREATE), "Create GPU timestamp clock");
    }

    @Override
    public long record() {
        if (clock == 0) throw new IllegalStateException("Timestamp clock is closed");
        return Sdl.require(
                JNI.invokePPP(clock, BackendInternals.timestampCommandBuffer(), RECORD), "Record GPU timestamp");
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
        BackendInternals.submitPendingCommands();
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

    private static long symbol(String name) {
        long address = SDL.getLibrary().getFunctionAddress(name);
        if (address == 0) throw new IllegalStateException("Missing native timestamp extension: " + name);
        return address;
    }
}
