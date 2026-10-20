package dev.fopwoc.julia.probe.scene;

import static org.lwjgl.opengl.GL11.GL_FALSE;
import static org.lwjgl.opengl.GL20.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Valid GLSL exceeding the backend's native sampler budget must escape mod-level link-status recovery. */
final class ShaderLimitChecks {
    private ShaderLimitChecks() {}

    static void run(Path output) throws IOException {
        Files.deleteIfExists(output.resolve("shader-limits.txt"));
        int vertex = glCreateShader(GL_VERTEX_SHADER), fragment = glCreateShader(GL_FRAGMENT_SHADER);
        int program = glCreateProgram();
        try {
            glShaderSource(vertex, "#version 330 core\nvoid main(){gl_Position=vec4(0,0,0,1);}");
            var source = new StringBuilder("#version 330 core\nout vec4 color;\n");
            for (int i = 0; i < 17; i++)
                source.append("uniform sampler2D image").append(i).append(";\n");
            source.append("void main(){color=vec4(0);");
            for (int i = 0; i < 17; i++)
                source.append("color+=texture(image").append(i).append(",vec2(0.5));");
            source.append("}");
            glShaderSource(fragment, source);
            for (int shader : new int[] {vertex, fragment}) {
                glCompileShader(shader);
                if (glGetShaderi(shader, GL_COMPILE_STATUS) == GL_FALSE)
                    throw new IllegalStateException(
                            "Sampler-limit test has invalid GLSL: " + glGetShaderInfoLog(shader));
                glAttachShader(program, shader);
            }
            try {
                glLinkProgram(program);
                throw new IllegalStateException("Native sampler overflow became a recoverable shader link error");
            } catch (UnsupportedOperationException expected) {
                if (!expected.getMessage().contains("Too many fragment shader texture samplers (17, maximum 16)")
                        || glGetProgrami(program, GL_LINK_STATUS) != GL_FALSE
                        || !glGetProgramInfoLog(program).contains("17, maximum 16"))
                    throw new IllegalStateException("Sampler failure lost its native diagnostic", expected);
            }
            Files.writeString(
                    output.resolve("shader-limits.txt"),
                    "PASS\nchecks=valid GLSL,17 active samplers,native translation rejection,exception escapes link-status recovery,original program info log\n");
        } finally {
            glDeleteProgram(program);
            glDeleteShader(vertex);
            glDeleteShader(fragment);
        }
    }
}
