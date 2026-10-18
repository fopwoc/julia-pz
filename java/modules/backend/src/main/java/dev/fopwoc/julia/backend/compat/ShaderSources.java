package dev.fopwoc.julia.backend.compat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/** Resolves the published backend's bundled shaders without Minecraft's resource loader. */
public final class ShaderSources {
    private ShaderSources() {}

    public static String getShaderSource(String identifier) {
        if (!identifier.matches("angelica:sdlgpu/[a-zA-Z0-9_/.-]+") || identifier.contains("..")) {
            throw new IllegalArgumentException("Unsupported backend shader: " + identifier);
        }
        String path = "/assets/angelica/shaders/" + identifier.substring("angelica:".length());
        try (var stream = ShaderSources.class.getResourceAsStream(path)) {
            if (stream == null) throw new IllegalStateException("Missing bundled backend shader: " + identifier);
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException failure) {
            throw new IllegalStateException("Cannot read bundled backend shader: " + identifier, failure);
        }
    }
}
