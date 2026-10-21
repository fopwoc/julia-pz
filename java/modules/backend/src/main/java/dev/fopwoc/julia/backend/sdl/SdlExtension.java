package dev.fopwoc.julia.backend.sdl;

import com.gtnewhorizons.angelica.sdlgpu.SDLGPUGate;
import dev.fopwoc.julia.backend.utils.Sdl;
import org.lwjgl.sdl.SDL;
import org.lwjgl.system.JNI;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

/**
 * Lookup of Julia's native extensions compiled into its own SDL build. Each extension exports the same C symbols for
 * every GPU backend that implements it, and a versioned ABI symbol that Java checks before the first call.
 */
public final class SdlExtension {
    private SdlExtension() {}

    public static long symbol(String name) {
        if (!name.equals("JULIA_SDL_PrepareDeveloperHud") && !name.equals("JULIA_SDL_ShowDeveloperHud")) {
            long device = SDLGPUGate.device().getDevice();
            if (device == 0)
                throw new IllegalStateException("Native GPU extension requested before device creation: " + name);
            long lookup = SDL.getLibrary().getFunctionAddress("JULIA_SDL_GetExtension");
            if (lookup == 0)
                throw new UnsupportedOperationException("Julia's SDL build lacks backend extension dispatch");
            try (var stack = MemoryStack.stackPush()) {
                long address = JNI.invokePPP(device, MemoryUtil.memAddress(stack.UTF8(name)), lookup);
                if (address == 0) throw new UnsupportedOperationException(Sdl.failure("Resolve " + name));
                return address;
            }
        }
        long address = SDL.getLibrary().getFunctionAddress(name);
        if (address == 0)
            throw new UnsupportedOperationException("Julia's SDL build does not export native extension " + name);
        return address;
    }

    public static void requireAbi(String name, int version) {
        int actual = JNI.invokeI(symbol(name));
        if (actual != version)
            throw new IllegalStateException(
                    "Unexpected native extension ABI " + name + ": " + actual + ", expected " + version);
    }
}
