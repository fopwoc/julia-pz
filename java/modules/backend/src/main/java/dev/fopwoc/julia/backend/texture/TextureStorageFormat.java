package dev.fopwoc.julia.backend.texture;

import static org.lwjgl.opengl.GL14.*;
import static org.lwjgl.opengl.GL30.*;
import static org.lwjgl.sdl.SDLGPU.*;

import java.util.function.IntPredicate;

/** Chooses physical storage without changing the requested GL format or dropping a depth/stencil aspect. */
public final class TextureStorageFormat {
    private TextureStorageFormat() {}

    public static int select(int format, IntPredicate supported) {
        int exact = TextureUploadFormat.storage(format);
        if (supported.test(exact)) return exact;
        // SDL only guarantees one of D24/D32 on each backend. D32 retains at least D24 depth precision.
        int promoted = switch (format) {
            case GL_DEPTH_COMPONENT24 -> SDL_GPU_TEXTUREFORMAT_D32_FLOAT;
            case GL_DEPTH24_STENCIL8 -> SDL_GPU_TEXTUREFORMAT_D32_FLOAT_S8_UINT;
            default -> 0;
        };
        if (promoted != 0 && supported.test(promoted)) return promoted;
        throw new UnsupportedOperationException("No compatible physical texture storage for GL format " + format);
    }
}
