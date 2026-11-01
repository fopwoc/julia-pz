package dev.fopwoc.julia.probe.scene;

import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL15.*;
import static org.lwjgl.opengl.GL20.*;
import static org.lwjgl.opengl.GL30.*;
import static org.lwjgl.opengl.GL33.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Exact Metal sample counts, including queries spanning render passes and draws issued before a query began. */
final class OcclusionQueryChecks {
    private OcclusionQueryChecks() {}

    static void run(Path output) throws IOException {
        Files.deleteIfExists(output.resolve("occlusion-queries.txt"));
        int program = program();
        int[] targets = {glGenTextures(), glGenTextures()}, framebuffers = {glGenFramebuffers(), glGenFramebuffers()};
        int samples = glGenQueries(), any = glGenQueries();
        try {
            for (int i = 0; i < 2; i++) {
                glBindTexture(GL_TEXTURE_2D, targets[i]);
                glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, 8, 8, 0, GL_RGBA, GL_UNSIGNED_BYTE, 0L);
                glBindFramebuffer(GL_FRAMEBUFFER, framebuffers[i]);
                glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, targets[i], 0);
            }
            glBindTexture(GL_TEXTURE_2D, 0);
            glBindFramebuffer(GL_FRAMEBUFFER, framebuffers[0]);
            glViewport(0, 0, 8, 8);
            glDisable(GL_DEPTH_TEST);
            glDisable(GL_BLEND);
            glDisable(GL_CULL_FACE);
            glDisable(GL_SCISSOR_TEST);
            glUseProgram(program);

            // Samples from a draw issued before the query began belong to no query.
            glDrawArrays(GL_TRIANGLES, 0, 3);
            expect(samples, GL_SAMPLES_PASSED, 0, () -> {});
            expect(samples, GL_SAMPLES_PASSED, 64, () -> glDrawArrays(GL_TRIANGLES, 0, 3));
            expect(samples, GL_SAMPLES_PASSED, 32, () -> {
                glEnable(GL_SCISSOR_TEST);
                glScissor(0, 0, 4, 8);
                glDrawArrays(GL_TRIANGLES, 0, 3);
                glDisable(GL_SCISSOR_TEST);
            });
            // Switching framebuffers splits the query across two native render passes.
            expect(samples, GL_SAMPLES_PASSED, 128, () -> {
                glDrawArrays(GL_TRIANGLES, 0, 3);
                glBindFramebuffer(GL_FRAMEBUFFER, framebuffers[1]);
                glDrawArrays(GL_TRIANGLES, 0, 3);
                glBindFramebuffer(GL_FRAMEBUFFER, framebuffers[0]);
            });
            expect(any, GL_ANY_SAMPLES_PASSED, 0, () -> {
                glEnable(GL_SCISSOR_TEST);
                glScissor(0, 0, 0, 0);
                glDrawArrays(GL_TRIANGLES, 0, 3);
                glDisable(GL_SCISSOR_TEST);
            });
            expect(any, GL_ANY_SAMPLES_PASSED, 1, () -> glDrawArrays(GL_TRIANGLES, 0, 3));
            if (glGetQueryObjecti64(samples, GL_QUERY_RESULT) != 128)
                throw new IllegalStateException("64-bit occlusion result changed after a later query");
            Files.writeString(
                    output.resolve("occlusion-queries.txt"),
                    "PASS\nchecks=exact sample counts,scissored draws,queries spanning render passes,draws before begin,"
                            + "any-samples booleans,availability,64-bit results\n");
        } finally {
            glUseProgram(0);
            glDisable(GL_SCISSOR_TEST);
            glBindFramebuffer(GL_FRAMEBUFFER, 0);
            glDeleteQueries(samples);
            glDeleteQueries(any);
            for (int framebuffer : framebuffers) glDeleteFramebuffers(framebuffer);
            for (int target : targets) glDeleteTextures(target);
            glDeleteProgram(program);
        }
    }

    private static void expect(int query, int target, long expected, Runnable draws) {
        glBeginQuery(target, query);
        draws.run();
        glEndQuery(target);
        long result = glGetQueryObjecti(query, GL_QUERY_RESULT);
        if (result != expected)
            throw new IllegalStateException("Occlusion target 0x" + Integer.toHexString(target) + " counted " + result
                    + ", expected " + expected);
        if (glGetQueryObjecti(query, GL_QUERY_RESULT_AVAILABLE) != 1)
            throw new IllegalStateException("Read occlusion result is unavailable");
    }

    private static int program() {
        int vertex = glCreateShader(GL_VERTEX_SHADER), fragment = glCreateShader(GL_FRAGMENT_SHADER);
        int program = glCreateProgram();
        glShaderSource(vertex, """
                #version 330 core
                void main() {
                    vec2 p = vec2((gl_VertexID << 1) & 2, gl_VertexID & 2);
                    gl_Position = vec4(p * 2.0 - 1.0, 0, 1);
                }
                """);
        glShaderSource(fragment, "#version 330 core\nout vec4 color;\nvoid main() { color = vec4(1); }\n");
        for (int shader : new int[] {vertex, fragment}) {
            glCompileShader(shader);
            if (glGetShaderi(shader, GL_COMPILE_STATUS) == GL_FALSE)
                throw new IllegalStateException(glGetShaderInfoLog(shader));
            glAttachShader(program, shader);
        }
        glLinkProgram(program);
        glDeleteShader(vertex);
        glDeleteShader(fragment);
        if (glGetProgrami(program, GL_LINK_STATUS) == GL_FALSE)
            throw new IllegalStateException(glGetProgramInfoLog(program));
        return program;
    }
}
