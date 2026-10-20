package dev.fopwoc.julia.backend.texture;

import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL14.*;
import static org.lwjgl.opengl.GL30.*;
import static org.lwjgl.sdl.SDLGPU.*;

/** Explicit storage and transfer formats prevent upstream's unknown-format RGBA fallback. */
public record TextureUploadFormat(int format, int type, int bytes) {
    public static int storage(int format) {
        return switch (format) {
            case GL_R8 -> SDL_GPU_TEXTUREFORMAT_R8_UNORM;
            case GL_R16 -> SDL_GPU_TEXTUREFORMAT_R16_UNORM;
            case GL_RG8 -> SDL_GPU_TEXTUREFORMAT_R8G8_UNORM;
            case GL_RGB8, GL_RGBA8 -> SDL_GPU_TEXTUREFORMAT_R8G8B8A8_UNORM;
            case GL_SRGB8_ALPHA8 -> SDL_GPU_TEXTUREFORMAT_R8G8B8A8_UNORM_SRGB;
            case GL_R16F -> SDL_GPU_TEXTUREFORMAT_R16_FLOAT;
            case GL_RG16F -> SDL_GPU_TEXTUREFORMAT_R16G16_FLOAT;
            case GL_RGBA16F -> SDL_GPU_TEXTUREFORMAT_R16G16B16A16_FLOAT;
            case GL_R32F -> SDL_GPU_TEXTUREFORMAT_R32_FLOAT;
            case GL_RG32F -> SDL_GPU_TEXTUREFORMAT_R32G32_FLOAT;
            case GL_RGBA32F -> SDL_GPU_TEXTUREFORMAT_R32G32B32A32_FLOAT;
            case GL_R8I -> SDL_GPU_TEXTUREFORMAT_R8_INT;
            case GL_RG8I -> SDL_GPU_TEXTUREFORMAT_R8G8_INT;
            case GL_RGBA8I -> SDL_GPU_TEXTUREFORMAT_R8G8B8A8_INT;
            case GL_R8UI -> SDL_GPU_TEXTUREFORMAT_R8_UINT;
            case GL_RG8UI -> SDL_GPU_TEXTUREFORMAT_R8G8_UINT;
            case GL_RGBA8UI -> SDL_GPU_TEXTUREFORMAT_R8G8B8A8_UINT;
            case GL_R16I -> SDL_GPU_TEXTUREFORMAT_R16_INT;
            case GL_RG16I -> SDL_GPU_TEXTUREFORMAT_R16G16_INT;
            case GL_RGBA16I -> SDL_GPU_TEXTUREFORMAT_R16G16B16A16_INT;
            case GL_R16UI -> SDL_GPU_TEXTUREFORMAT_R16_UINT;
            case GL_RG16UI -> SDL_GPU_TEXTUREFORMAT_R16G16_UINT;
            case GL_RGBA16UI -> SDL_GPU_TEXTUREFORMAT_R16G16B16A16_UINT;
            case GL_R32I -> SDL_GPU_TEXTUREFORMAT_R32_INT;
            case GL_RG32I -> SDL_GPU_TEXTUREFORMAT_R32G32_INT;
            case GL_RGBA32I -> SDL_GPU_TEXTUREFORMAT_R32G32B32A32_INT;
            case GL_R32UI -> SDL_GPU_TEXTUREFORMAT_R32_UINT;
            case GL_RG32UI -> SDL_GPU_TEXTUREFORMAT_R32G32_UINT;
            case GL_RGBA32UI -> SDL_GPU_TEXTUREFORMAT_R32G32B32A32_UINT;
            case GL_DEPTH_COMPONENT16 -> SDL_GPU_TEXTUREFORMAT_D16_UNORM;
            case GL_DEPTH_COMPONENT24 -> SDL_GPU_TEXTUREFORMAT_D24_UNORM;
            case GL_DEPTH_COMPONENT32F -> SDL_GPU_TEXTUREFORMAT_D32_FLOAT;
            case GL_DEPTH24_STENCIL8 -> SDL_GPU_TEXTUREFORMAT_D24_UNORM_S8_UINT;
            case GL_DEPTH32F_STENCIL8 -> SDL_GPU_TEXTUREFORMAT_D32_FLOAT_S8_UINT;
            default ->
                throw new UnsupportedOperationException("Named texture storage format not implemented: " + format);
        };
    }

    public static boolean depth(int format) {
        return format == GL_DEPTH_COMPONENT16
                || format == GL_DEPTH_COMPONENT24
                || format == GL_DEPTH_COMPONENT32F
                || format == GL_DEPTH24_STENCIL8
                || format == GL_DEPTH32F_STENCIL8;
    }

    public static TextureUploadFormat transfer(int storage) {
        return switch (storage) {
            case GL_R8 -> new TextureUploadFormat(GL_RED, GL_UNSIGNED_BYTE, 1);
            case GL_R16 -> new TextureUploadFormat(GL_RED, GL_UNSIGNED_SHORT, 2);
            case GL_RG8 -> new TextureUploadFormat(GL_RG, GL_UNSIGNED_BYTE, 2);
            case GL_RGB8 -> new TextureUploadFormat(GL_RGB, GL_UNSIGNED_BYTE, 3);
            case GL_RGBA8, GL_SRGB8_ALPHA8 -> new TextureUploadFormat(GL_RGBA, GL_UNSIGNED_BYTE, 4);
            case GL_R16F -> new TextureUploadFormat(GL_RED, GL_HALF_FLOAT, 2);
            case GL_RG16F -> new TextureUploadFormat(GL_RG, GL_HALF_FLOAT, 4);
            case GL_RGBA16F -> new TextureUploadFormat(GL_RGBA, GL_HALF_FLOAT, 8);
            case GL_R32F -> new TextureUploadFormat(GL_RED, GL_FLOAT, 4);
            case GL_RG32F -> new TextureUploadFormat(GL_RG, GL_FLOAT, 8);
            case GL_RGBA32F -> new TextureUploadFormat(GL_RGBA, GL_FLOAT, 16);
            case GL_R8I -> new TextureUploadFormat(GL_RED_INTEGER, GL_BYTE, 1);
            case GL_RG8I -> new TextureUploadFormat(GL_RG_INTEGER, GL_BYTE, 2);
            case GL_RGBA8I -> new TextureUploadFormat(GL_RGBA_INTEGER, GL_BYTE, 4);
            case GL_R8UI -> new TextureUploadFormat(GL_RED_INTEGER, GL_UNSIGNED_BYTE, 1);
            case GL_RG8UI -> new TextureUploadFormat(GL_RG_INTEGER, GL_UNSIGNED_BYTE, 2);
            case GL_RGBA8UI -> new TextureUploadFormat(GL_RGBA_INTEGER, GL_UNSIGNED_BYTE, 4);
            case GL_R16I -> new TextureUploadFormat(GL_RED_INTEGER, GL_SHORT, 2);
            case GL_RG16I -> new TextureUploadFormat(GL_RG_INTEGER, GL_SHORT, 4);
            case GL_RGBA16I -> new TextureUploadFormat(GL_RGBA_INTEGER, GL_SHORT, 8);
            case GL_R16UI -> new TextureUploadFormat(GL_RED_INTEGER, GL_UNSIGNED_SHORT, 2);
            case GL_RG16UI -> new TextureUploadFormat(GL_RG_INTEGER, GL_UNSIGNED_SHORT, 4);
            case GL_RGBA16UI -> new TextureUploadFormat(GL_RGBA_INTEGER, GL_UNSIGNED_SHORT, 8);
            case GL_R32I -> new TextureUploadFormat(GL_RED_INTEGER, GL_INT, 4);
            case GL_RG32I -> new TextureUploadFormat(GL_RG_INTEGER, GL_INT, 8);
            case GL_RGBA32I -> new TextureUploadFormat(GL_RGBA_INTEGER, GL_INT, 16);
            case GL_R32UI -> new TextureUploadFormat(GL_RED_INTEGER, GL_UNSIGNED_INT, 4);
            case GL_RG32UI -> new TextureUploadFormat(GL_RG_INTEGER, GL_UNSIGNED_INT, 8);
            case GL_RGBA32UI -> new TextureUploadFormat(GL_RGBA_INTEGER, GL_UNSIGNED_INT, 16);
            default ->
                throw new UnsupportedOperationException("Named texture upload format not implemented: " + storage);
        };
    }
}
