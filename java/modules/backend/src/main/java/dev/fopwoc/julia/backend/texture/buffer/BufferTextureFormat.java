package dev.fopwoc.julia.backend.texture.buffer;

import static org.lwjgl.opengl.GL30.*;
import static org.lwjgl.opengl.GL31.*;
import static org.lwjgl.sdl.SDLGPU.*;

import java.util.List;

/** Supported typed views have identical GL and Metal storage layouts. */
public enum BufferTextureFormat {
    R32F(GL_R32F, SDL_GPU_TEXTUREFORMAT_R32_FLOAT, 4),
    RG32F(GL_RG32F, SDL_GPU_TEXTUREFORMAT_R32G32_FLOAT, 8),
    RGBA32F(GL_RGBA32F, SDL_GPU_TEXTUREFORMAT_R32G32B32A32_FLOAT, 16),
    R32I(GL_R32I, SDL_GPU_TEXTUREFORMAT_R32_INT, 4),
    R16I(GL_R16I, SDL_GPU_TEXTUREFORMAT_R16_INT, 2),
    RG32I(GL_RG32I, SDL_GPU_TEXTUREFORMAT_R32G32_INT, 8),
    RGBA32I(GL_RGBA32I, SDL_GPU_TEXTUREFORMAT_R32G32B32A32_INT, 16),
    RGBA16UI(GL_RGBA16UI, SDL_GPU_TEXTUREFORMAT_R16G16B16A16_UINT, 8),
    R16UI(GL_R16UI, SDL_GPU_TEXTUREFORMAT_R16_UINT, 2),
    R32UI(GL_R32UI, SDL_GPU_TEXTUREFORMAT_R32_UINT, 4),
    RG32UI(GL_RG32UI, SDL_GPU_TEXTUREFORMAT_R32G32_UINT, 8),
    RGBA32UI(GL_RGBA32UI, SDL_GPU_TEXTUREFORMAT_R32G32B32A32_UINT, 16);

    public static final List<BufferTextureFormat> SUPPORTED = List.of(values());
    private final int gl, sdl, bytes;

    BufferTextureFormat(int gl, int sdl, int bytes) {
        this.gl = gl;
        this.sdl = sdl;
        this.bytes = bytes;
    }

    public int gl() {
        return gl;
    }

    public int sdl() {
        return sdl;
    }

    public int bytes() {
        return bytes;
    }

    /** The buffer sampler type that reads this format without conversion. */
    public int samplerType() {
        if (name().endsWith("UI")) return GL_UNSIGNED_INT_SAMPLER_BUFFER;
        return name().endsWith("I") ? GL_INT_SAMPLER_BUFFER : GL_SAMPLER_BUFFER;
    }

    public static BufferTextureFormat get(int gl) {
        for (var format : SUPPORTED) if (format.gl == gl) return format;
        throw new UnsupportedOperationException(
                "Buffer texture format is not implemented: 0x" + Integer.toHexString(gl));
    }
}
