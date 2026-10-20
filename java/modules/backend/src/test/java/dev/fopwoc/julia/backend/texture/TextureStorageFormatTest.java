package dev.fopwoc.julia.backend.texture;

import static org.junit.jupiter.api.Assertions.*;
import static org.lwjgl.opengl.GL14.*;
import static org.lwjgl.opengl.GL30.*;
import static org.lwjgl.sdl.SDLGPU.*;

import org.junit.jupiter.api.Test;

class TextureStorageFormatTest {
    @Test
    void keepsSupportedExactDepthStorage() {
        assertEquals(SDL_GPU_TEXTUREFORMAT_D24_UNORM, TextureStorageFormat.select(GL_DEPTH_COMPONENT24, value -> true));
    }

    @Test
    void promotesDepthPrecisionAndPreservesStencil() {
        assertEquals(
                SDL_GPU_TEXTUREFORMAT_D32_FLOAT,
                TextureStorageFormat.select(GL_DEPTH_COMPONENT24, value -> value == SDL_GPU_TEXTUREFORMAT_D32_FLOAT));
        assertEquals(
                SDL_GPU_TEXTUREFORMAT_D32_FLOAT_S8_UINT,
                TextureStorageFormat.select(
                        GL_DEPTH24_STENCIL8, value -> value == SDL_GPU_TEXTUREFORMAT_D32_FLOAT_S8_UINT));
        assertThrows(
                UnsupportedOperationException.class,
                () -> TextureStorageFormat.select(
                        GL_DEPTH24_STENCIL8, value -> value == SDL_GPU_TEXTUREFORMAT_D32_FLOAT));
    }

    @Test
    void refusesUnsupportedStorageRatherThanLosingPrecisionOrChangingColorFormats() {
        assertThrows(
                UnsupportedOperationException.class,
                () -> TextureStorageFormat.select(
                        GL_DEPTH_COMPONENT32F, value -> value == SDL_GPU_TEXTUREFORMAT_D16_UNORM));
        assertThrows(
                UnsupportedOperationException.class,
                () -> TextureStorageFormat.select(GL_RGBA32F, value -> value == SDL_GPU_TEXTUREFORMAT_R8G8B8A8_UNORM));
        assertThrows(
                UnsupportedOperationException.class,
                () -> TextureStorageFormat.select(GL_DEPTH_COMPONENT24, value -> false));
    }
}
