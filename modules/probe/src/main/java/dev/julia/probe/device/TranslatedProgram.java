package dev.julia.probe.device;

import com.gtnewhorizons.angelica.sdlgpu.device.Device;
import com.gtnewhorizons.angelica.sdlgpu.shader.ShaderManager;
import dev.julia.backend.utils.Sdl;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import static org.lwjgl.opengl.GL20.GL_FRAGMENT_SHADER;
import static org.lwjgl.opengl.GL20.GL_VERTEX_SHADER;
import static org.lwjgl.sdl.SDLGPU.*;

/** Owns Angelica's OpenGL GLSL transformation, linking, and Metal shader handles. */
final class TranslatedProgram implements AutoCloseable {
    private final ShaderManager shaders;
    private final ShaderManager.ProgramObject program;

    TranslatedProgram(Device device) throws IOException {
        shaders = new ShaderManager(device);
        try {
            int vertex = compile(GL_VERTEX_SHADER, "/shaders/texture.vert");
            int fragment = compile(GL_FRAGMENT_SHADER, "/shaders/texture.frag");
            int id = shaders.createProgram();
            shaders.attachShader(id, vertex);
            shaders.attachShader(id, fragment);
            shaders.linkProgram(id);
            program = shaders.getProgram(id);
            if (program == null || !program.linked) {
                throw new IllegalStateException("Angelica shader link failed: " + shaders.getProgramInfoLog(id));
            }
            var vr = program.vertexResources;
            var fr = program.fragmentResources;
            if (vr.numSamplers() != 0 || fr.numSamplers() != 1 || vr.numUBOs() != 0 || fr.numUBOs() != 0) {
                throw new IllegalStateException("Unexpected translated shader resources: " + vr + "; " + fr);
            }
            program.sdlVertexShader = Sdl.require(shaders.createSDLShader(program.vertexSpirv,
                    SDL_GPU_SHADERSTAGE_VERTEX, vr.numSamplers(), vr.numUBOs(), vr.numStorageBuffers(),
                    vr.numStorageTextures()), "Create translated vertex shader");
            program.sdlFragmentShader = Sdl.require(shaders.createSDLShader(program.fragmentSpirv,
                    SDL_GPU_SHADERSTAGE_FRAGMENT, fr.numSamplers(), fr.numUBOs(), fr.numStorageBuffers(),
                    fr.numStorageTextures()), "Create translated fragment shader");
        } catch (IOException | RuntimeException | Error failure) {
            shaders.shutdown();
            throw failure;
        }
    }

    private int compile(int type, String resource) throws IOException {
        String source;
        try (var stream = TranslatedProgram.class.getResourceAsStream(resource)) {
            if (stream == null) throw new IOException("Missing shader " + resource);
            source = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
        int shader = shaders.createShader(type);
        shaders.shaderSource(shader, source);
        shaders.compileShader(shader);
        String error = shaders.getShaderInfoLog(shader);
        if (!error.isEmpty()) throw new IllegalStateException(resource + ": " + error);
        return shader;
    }

    long vertex() { return program.sdlVertexShader; }
    long fragment() { return program.sdlFragmentShader; }

    @Override
    public void close() { shaders.shutdown(); }
}
