package dev.fopwoc.julia.backend.texture.macos;

import com.gtnewhorizons.angelica.sdlgpu.SDLGPUGate;
import dev.fopwoc.julia.backend.texture.buffer.BufferTextureDriver;
import dev.fopwoc.julia.backend.texture.buffer.BufferTextureFormat;
import dev.fopwoc.julia.backend.utils.Sdl;
import org.lwjgl.sdl.SDL;
import org.lwjgl.sdl.SDLGPU;
import org.lwjgl.system.JNI;

public final class MetalBufferTextures implements BufferTextureDriver {
    private static final long LIMIT = symbol("JULIA_SDL_BufferTextureLimit");
    private static final long ALIGNMENT = symbol("JULIA_SDL_BufferTextureAlignment");
    private static final long IDENTITY = symbol("JULIA_SDL_BufferStorageIdentity");
    private static final long CREATE = symbol("JULIA_SDL_CreateBufferTexture");

    public MetalBufferTextures() {
        if (JNI.invokeI(symbol("JULIA_SDL_BufferTextureABI")) != 1)
            throw new IllegalStateException("Unexpected native buffer-texture ABI");
    }

    private static long device() {
        return SDLGPUGate.device().getDevice();
    }

    public int limit() {
        return JNI.invokePI(device(), LIMIT);
    }

    public int alignment(BufferTextureFormat format) {
        int value = JNI.invokePI(device(), format.sdl(), ALIGNMENT);
        if (value <= 0) throw Sdl.failure("Query buffer texture alignment");
        return value;
    }

    public long identity(long buffer) {
        return JNI.invokePP(buffer, IDENTITY);
    }

    public long create(long buffer, BufferTextureFormat format, int offset, int texels) {
        return Sdl.require(
                JNI.invokePPP(device(), buffer, format.sdl(), offset, texels, format.bytes(), CREATE),
                "Create Metal buffer texture");
    }

    public void release(long texture) {
        if (texture != 0) SDLGPU.SDL_ReleaseGPUTexture(device(), texture);
    }

    private static long symbol(String name) {
        long address = SDL.getLibrary().getFunctionAddress(name);
        if (address == 0) throw new IllegalStateException("Missing native buffer-texture extension: " + name);
        return address;
    }
}
