package dev.fopwoc.julia.probe.scene;

import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL12.GL_CLAMP_TO_EDGE;
import static org.lwjgl.opengl.GL13.*;
import static org.lwjgl.opengl.GL20.*;
import static org.lwjgl.opengl.GL30.*;
import static org.lwjgl.opengl.GL42.glTexStorage2D;

import dev.fopwoc.julia.probe.adapter.ResourceReadback;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.lwjgl.system.MemoryStack;

/** Helper discards must retain the background and leave depth available for a later draw. */
final class FragmentDiscardChecks {
    private static final int WIDTH = 16, HEIGHT = 8;
    private static final String VERTEX = """
            #version 450 core
            out vec2 uv;
            void main(){vec2 p=vec2((gl_VertexID<<1)&2,gl_VertexID&2);uv=p;gl_Position=vec4(p*2.-1.,0,1);}
            """;
    private static final String FRAGMENT = """
            #version 450 core
            in vec2 uv;uniform sampler2D images[8];uniform int selected;uniform int mode;
            out vec4 color;
            vec4 cutout(){vec4 c=texture(images[selected],uv);if(c.a<.5)discard;return vec4(c.rgb/c.a,1);}
            float heightAt(vec2 p){if(p.x<0.)return 0.;return texelFetch(images[7],ivec2(0),0).r;}
            bool classify(vec3 art){if(art.b<=art.r)return false;return heightAt(uv)<=heightAt(uv+1.);}
            void main(){HELPER_QUERY vec4 c=cutout();bool marked=classify(c.rgb);
              if(mode!=0){color=vec4(c.rgb,marked?.75:0.);return;}
              color=vec4(c.rgb,marked?.75:0.);}
            """;

    private FragmentDiscardChecks() {}

    static void run(Path output) throws IOException {
        int source = glGenTextures(), target = glGenTextures(), depth = glGenTextures(), fbo = glGenFramebuffers();
        int sky = program(VERTEX.replace("vec4(p*2.-1.,0,1)", "vec4(p*2.-1.,.5,1)"), """
                #version 450 core
                out vec4 color;void main(){color=vec4(0,1,0,1);}
                """);
        int verified = 0;
        try (var stack = MemoryStack.stackPush()) {
            glActiveTexture(GL_TEXTURE0);
            glBindTexture(GL_TEXTURE_2D, source);
            glTexImage2D(
                    GL_TEXTURE_2D,
                    0,
                    GL_RGBA8,
                    2,
                    1,
                    0,
                    GL_RGBA,
                    GL_UNSIGNED_BYTE,
                    stack.bytes((byte) 255, (byte) 0, (byte) 0, (byte) 0, (byte) 255, (byte) 0, (byte) 0, (byte) 255));
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
            glBindTexture(GL_TEXTURE_2D, target);
            glTexStorage2D(GL_TEXTURE_2D, 1, GL_RGBA8, WIDTH, HEIGHT);
            glBindTexture(GL_TEXTURE_2D, depth);
            glTexStorage2D(GL_TEXTURE_2D, 1, GL_DEPTH_COMPONENT32F, WIDTH, HEIGHT);
            glBindFramebuffer(GL_FRAMEBUFFER, fbo);
            glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, target, 0);
            glFramebufferTexture2D(GL_FRAMEBUFFER, GL_DEPTH_ATTACHMENT, GL_TEXTURE_2D, depth, 0);
            glDrawBuffer(GL_COLOR_ATTACHMENT0);
            glBindTexture(GL_TEXTURE_2D, source);
            glViewport(0, 0, WIDTH, HEIGHT);
            glDisable(GL_BLEND);
            glDisable(GL_CULL_FACE);
            glDisable(GL_STENCIL_TEST);
            glDisable(GL_SCISSOR_TEST);
            glEnable(GL_DEPTH_TEST);
            glDepthFunc(GL_LESS);
            glDepthMask(true);
            glColorMask(true, true, true, true);
            glClearDepth(1);
            for (boolean existingHelper : new boolean[] {false, true}) {
                int shader = program(
                        VERTEX,
                        FRAGMENT.replace("HELPER_QUERY", existingHelper ? "if(gl_HelperInvocation)discard;" : ""));
                try {
                    glUseProgram(shader);
                    glUniform1iv(glGetUniformLocation(shader, "images"), new int[8]);
                    for (int mode = 0; mode < 2; mode++) {
                        glUseProgram(shader);
                        glUniform1i(glGetUniformLocation(shader, "mode"), mode);
                        glClearColor(0, 0, 1, 1);
                        glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);
                        glDrawArrays(GL_TRIANGLES, 0, 3);
                        verify(target, false);
                        verified += WIDTH * HEIGHT;
                        glUseProgram(sky);
                        glDrawArrays(GL_TRIANGLES, 0, 3);
                        verify(target, true);
                        verified += WIDTH * HEIGHT;
                    }
                } finally {
                    glUseProgram(0);
                    glDeleteProgram(shader);
                }
            }
            Files.writeString(
                    output.resolve("fragment-discard.txt"),
                    "PASS: helper discard colour/depth preservation, early returns, existing helper input; pixels="
                            + verified + "\n");
        } finally {
            glUseProgram(0);
            glBindFramebuffer(GL_FRAMEBUFFER, 0);
            glDisable(GL_DEPTH_TEST);
            glDeleteProgram(sky);
            glDeleteFramebuffers(fbo);
            glDeleteTextures(source);
            glDeleteTextures(target);
            glDeleteTextures(depth);
        }
    }

    private static void verify(int target, boolean sky) {
        byte[] data = ResourceReadback.layer(target, 0);
        for (int y = 0; y < HEIGHT; y++)
            for (int x = 0; x < WIDTH; x++) {
                int at = (y * WIDTH + x) * 4;
                boolean cut = x < WIDTH / 2;
                if ((data[at] & 255) != (cut ? 0 : 255)
                        || (data[at + 1] & 255) != (cut && sky ? 255 : 0)
                        || (data[at + 2] & 255) != (cut && !sky ? 255 : 0))
                    throw new IllegalStateException(
                            "Discarded fragment changed colour/depth at " + x + "," + y + "; sky=" + sky);
            }
    }

    private static int program(String vertex, String fragment) {
        int program = glCreateProgram();
        try {
            for (int type : new int[] {GL_VERTEX_SHADER, GL_FRAGMENT_SHADER}) {
                int shader = glCreateShader(type);
                try {
                    glShaderSource(shader, type == GL_VERTEX_SHADER ? vertex : fragment);
                    glCompileShader(shader);
                    if (glGetShaderi(shader, GL_COMPILE_STATUS) == 0)
                        throw new IllegalStateException(glGetShaderInfoLog(shader));
                    glAttachShader(program, shader);
                } finally {
                    glDeleteShader(shader);
                }
            }
            glLinkProgram(program);
            if (glGetProgrami(program, GL_LINK_STATUS) == 0)
                throw new IllegalStateException(glGetProgramInfoLog(program));
            return program;
        } catch (RuntimeException | Error failure) {
            glDeleteProgram(program);
            throw failure;
        }
    }
}
