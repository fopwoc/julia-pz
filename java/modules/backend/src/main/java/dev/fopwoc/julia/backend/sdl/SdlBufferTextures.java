package dev.fopwoc.julia.backend.sdl;

import com.gtnewhorizons.angelica.sdlgpu.SDLGPUGate;
import dev.fopwoc.julia.backend.texture.buffer.BufferTextureDriver;
import dev.fopwoc.julia.backend.texture.buffer.BufferTextureFormat;
import dev.fopwoc.julia.backend.utils.Sdl;
import org.lwjgl.sdl.SDLGPU;
import org.lwjgl.system.JNI;

public final class SdlBufferTextures implements BufferTextureDriver {
    private static final long LIMIT = SdlExtension.symbol("JULIA_SDL_BufferTextureLimit");
    private static final long ALIGNMENT = SdlExtension.symbol("JULIA_SDL_BufferTextureAlignment");
    private static final long IDENTITY = SdlExtension.symbol("JULIA_SDL_BufferStorageIdentity");
    private static final long CREATE = SdlExtension.symbol("JULIA_SDL_CreateBufferTexture");

    public SdlBufferTextures() {
        SdlExtension.requireAbi("JULIA_SDL_BufferTextureABI", 1);
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
                "Create native buffer texture");
    }

    public void release(long texture) {
        if (texture != 0) SDLGPU.SDL_ReleaseGPUTexture(device(), texture);
    }
}
