package dev.fopwoc.julia.backend.compat;

import org.lwjgl.sdl.SDL;
import org.lwjgl.system.JNI;

/** Preserves the unsigned stencil reference across the native call boundary. */
public final class StencilReference {
    private static final long FUNCTION = SDL.getLibrary().getFunctionAddress("SDL_SetGPUStencilReference");

    private StencilReference() {}

    public static void set(long pass, byte reference) {
        if (pass == 0 || FUNCTION == 0) throw new IllegalStateException("Invalid SDL stencil-reference call");
        // The bundled byte trampoline sign-extends 128..255 on macOS ARM64. C's Uint8 argument
        // expects a zero-extended register value; widening its unsigned value preserves that ABI.
        JNI.invokePV(pass, Byte.toUnsignedInt(reference), FUNCTION);
    }
}
