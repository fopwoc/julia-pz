package dev.fopwoc.julia.backend.layer;

import static org.lwjgl.opengl.GL12.*;
import static org.lwjgl.opengl.GL30.*;

import com.gtnewhorizons.angelica.sdlgpu.SDLGPURenderBackend;
import dev.fopwoc.julia.backend.Subsystems;

/** Base of Julia's backend layers: owns {@link Subsystems} and GL errors raised by Julia's own validation. */
public abstract class JuliaLayer extends SDLGPURenderBackend {
    private final Subsystems subsystems = new Subsystems();
    private int error;

    /** Julia's state for this backend; static hooks use {@link Subsystems#current()}. */
    public Subsystems subsystems() {
        return subsystems;
    }

    /** Keeps the first GL error until {@code glGetError} reads it, as GL does. */
    protected void recordError(int code) {
        if (error == 0) error = code;
    }

    @Override
    public int getError() {
        int result = error;
        error = 0;
        return result != 0 ? result : super.getError();
    }
}
