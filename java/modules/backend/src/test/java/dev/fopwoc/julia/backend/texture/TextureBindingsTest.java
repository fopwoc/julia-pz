package dev.fopwoc.julia.backend.texture;

import static org.junit.jupiter.api.Assertions.*;
import static org.lwjgl.opengl.GL11.GL_TEXTURE_2D;
import static org.lwjgl.opengl.GL12.GL_TEXTURE_3D;

import com.gtnewhorizons.angelica.sdlgpu.frame.ContextState;
import java.util.ArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.lwjgl.system.MemoryUtil;

class TextureBindingsTest {
    private final ArrayList<ContextState> states = new ArrayList<>();

    private ContextState context() {
        var state = new ContextState();
        states.add(state);
        return state;
    }

    @AfterEach
    void releaseContexts() {
        for (var state : states) {
            MemoryUtil.nmemFree(state.vboBindingsAddr);
            state.eboBinding.free();
            state.cachedViewport.free();
            state.cachedScissor.free();
            state.cachedBlendColor.free();
        }
    }

    @Test
    void targetsUnitsAndContextsAreIndependent() {
        var bindings = new TextureBindings();
        var first = context();
        var second = context();
        bindings.bind(first, 0, GL_TEXTURE_2D, 7);
        bindings.bind(first, 0, GL_TEXTURE_3D, 9);
        bindings.bind(first, 1, GL_TEXTURE_2D, 11);
        assertEquals(7, bindings.get(first, 0, GL_TEXTURE_2D));
        assertEquals(9, bindings.get(first, 0, GL_TEXTURE_3D));
        assertEquals(11, bindings.get(first, 1, GL_TEXTURE_2D));
        assertEquals(0, bindings.get(second, 0, GL_TEXTURE_2D));
        bindings.samplerOverride(first, true);
        assertTrue(bindings.samplerOverride(first));
        assertFalse(bindings.samplerOverride(second));
        bindings.samplerOverride(first, false);
        assertFalse(bindings.samplerOverride(first));
        bindings.bind(first, 0, GL_TEXTURE_3D, 0);
        assertEquals(7, bindings.get(first, 0, GL_TEXTURE_2D));
    }

    @Test
    void deletionAndUnitUnbindingPreserveUnrelatedState() {
        var bindings = new TextureBindings();
        var state = context();
        bindings.bind(state, 0, GL_TEXTURE_2D, 7);
        bindings.bind(state, 1, GL_TEXTURE_2D, 7);
        bindings.bind(state, 0, GL_TEXTURE_3D, 9);
        bindings.deleted(state, 7);
        assertEquals(0, bindings.get(state, 0, GL_TEXTURE_2D));
        assertEquals(0, bindings.get(state, 1, GL_TEXTURE_2D));
        assertEquals(9, bindings.get(state, 0, GL_TEXTURE_3D));
        bindings.unbindUnit(state, 0);
        assertEquals(0, bindings.get(state, 0, GL_TEXTURE_3D));
    }
}
