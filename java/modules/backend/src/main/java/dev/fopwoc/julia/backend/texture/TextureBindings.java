package dev.fopwoc.julia.backend.texture;

import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL12.*;
import static org.lwjgl.opengl.GL13.*;
import static org.lwjgl.opengl.GL30.*;
import static org.lwjgl.opengl.GL31.*;
import static org.lwjgl.opengl.GL32.*;
import static org.lwjgl.opengl.GL40.*;

import com.gtnewhorizons.angelica.sdlgpu.frame.ContextState;
import java.util.WeakHashMap;

/** Logical bindings are per context, unit and target; the upstream single slot is only an execution mirror. */
public final class TextureBindings {
    public static final int UNIT_COUNT = 256;

    private static final class Bindings {
        final int[][] units;
        boolean samplerOverride;

        Bindings(int count) {
            units = new int[10][count];
        }
    }

    private final WeakHashMap<ContextState, Bindings> contexts = new WeakHashMap<>();

    private Bindings entry(ContextState state) {
        return contexts.computeIfAbsent(state, ignored -> new Bindings(state.boundTextures.length));
    }

    public void samplerOverride(ContextState state, boolean active) {
        entry(state).samplerOverride = active;
    }

    public boolean samplerOverride(ContextState state) {
        return entry(state).samplerOverride;
    }

    private int[][] bank(ContextState state) {
        return entry(state).units;
    }

    public int get(ContextState state, int unit, int target) {
        int[][] bank = bank(state);
        if (unit < 0 || unit >= state.boundTextures.length)
            throw new IllegalArgumentException("Texture unit out of range: " + unit);
        return bank[slot(target)][unit];
    }

    public void bind(ContextState state, int unit, int target, int name) {
        int[][] bank = bank(state);
        if (unit < 0 || unit >= state.boundTextures.length)
            throw new IllegalArgumentException("Texture unit out of range: " + unit);
        int slot = slot(target);
        if (bank[slot][unit] == name) return;
        bank[slot][unit] = name;
        state.samplerBindGen++;
    }

    public void deleted(ContextState state, int name) {
        if (name == 0) return;
        int[][] bank = bank(state);
        for (int[] target : bank)
            for (int unit = 0; unit < target.length; unit++)
                if (target[unit] == name) {
                    target[unit] = 0;
                    state.samplerBindGen++;
                }
    }

    public void unbindUnit(ContextState state, int unit) {
        int[][] bank = bank(state);
        if (unit < 0 || unit >= state.boundTextures.length)
            throw new IllegalArgumentException("Texture unit out of range: " + unit);
        for (int[] target : bank)
            if (target[unit] != 0) {
                target[unit] = 0;
                state.samplerBindGen++;
            }
    }

    public static int queryTarget(int parameter) {
        return switch (parameter) {
            case GL_TEXTURE_2D, GL_TEXTURE_BINDING_2D -> GL_TEXTURE_2D;
            case GL_TEXTURE_BINDING_1D -> GL_TEXTURE_1D;
            case GL_TEXTURE_BINDING_3D -> GL_TEXTURE_3D;
            case GL_TEXTURE_BINDING_CUBE_MAP -> GL_TEXTURE_CUBE_MAP;
            case GL_TEXTURE_BINDING_1D_ARRAY -> GL_TEXTURE_1D_ARRAY;
            case GL_TEXTURE_BINDING_2D_ARRAY -> GL_TEXTURE_2D_ARRAY;
            case GL_TEXTURE_BINDING_RECTANGLE -> GL_TEXTURE_RECTANGLE;
            case GL_TEXTURE_BINDING_CUBE_MAP_ARRAY -> GL_TEXTURE_CUBE_MAP_ARRAY;
            case GL_TEXTURE_BINDING_2D_MULTISAMPLE -> GL_TEXTURE_2D_MULTISAMPLE;
            case GL_TEXTURE_BINDING_2D_MULTISAMPLE_ARRAY -> GL_TEXTURE_2D_MULTISAMPLE_ARRAY;
            default -> 0;
        };
    }

    public static boolean selectable(int target) {
        return switch (target) {
            case GL_TEXTURE_1D,
                    GL_TEXTURE_2D,
                    GL_TEXTURE_3D,
                    GL_TEXTURE_CUBE_MAP,
                    GL_TEXTURE_1D_ARRAY,
                    GL_TEXTURE_2D_ARRAY,
                    GL_TEXTURE_RECTANGLE,
                    GL_TEXTURE_CUBE_MAP_ARRAY,
                    GL_TEXTURE_2D_MULTISAMPLE,
                    GL_TEXTURE_2D_MULTISAMPLE_ARRAY -> true;
            default -> false;
        };
    }

    private static int slot(int target) {
        return switch (target) {
            case GL_TEXTURE_2D -> 0;
            case GL_TEXTURE_3D -> 1;
            case GL_TEXTURE_2D_ARRAY -> 2;
            case GL_TEXTURE_CUBE_MAP -> 3;
            case GL_TEXTURE_1D -> 4;
            case GL_TEXTURE_1D_ARRAY -> 5;
            case GL_TEXTURE_RECTANGLE -> 6;
            case GL_TEXTURE_CUBE_MAP_ARRAY -> 7;
            case GL_TEXTURE_2D_MULTISAMPLE -> 8;
            case GL_TEXTURE_2D_MULTISAMPLE_ARRAY -> 9;
            default -> throw new UnsupportedOperationException("Texture binding target not implemented: " + target);
        };
    }
}
