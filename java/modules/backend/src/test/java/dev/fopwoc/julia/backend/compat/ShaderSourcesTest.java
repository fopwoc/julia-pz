package dev.fopwoc.julia.backend.compat;

import static org.junit.jupiter.api.Assertions.*;

import com.gtnewhorizons.angelica.glsm.shader.ShaderSources;
import org.junit.jupiter.api.Test;

class ShaderSourcesTest {
    @Test
    void loadsBundledShadersWithoutMinecraft() {
        assertTrue(ShaderSources.getShaderSource("angelica:combined_glint.frag.glsl")
                .contains("GLINT"));
        assertTrue(ShaderSources.getShaderSource("angelica:sdlgpu/clear_attachment.vsh")
                .contains("gl_Position"));
        assertTrue(ShaderSources.getShaderSource("angelica:sdlgpu/clear_attachment.fsh")
                .contains("#version"));
    }

    @Test
    void rejectsInvalidOrMissingResourceNames() {
        assertThrows(
                IllegalArgumentException.class, () -> ShaderSources.getShaderSource("angelica:sdlgpu/../../other"));
        assertThrows(IllegalArgumentException.class, () -> ShaderSources.getShaderSource("other:sdlgpu/shader"));
        assertThrows(IllegalStateException.class, () -> ShaderSources.getShaderSource("angelica:sdlgpu/absent.fsh"));
    }
}
