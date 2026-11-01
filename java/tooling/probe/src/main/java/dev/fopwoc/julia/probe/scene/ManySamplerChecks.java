package dev.fopwoc.julia.probe.scene;

import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL13.*;
import static org.lwjgl.opengl.GL20.*;
import static org.lwjgl.opengl.GL30.*;
import static org.lwjgl.opengl.GL33.glBindSampler;
import static org.lwjgl.opengl.GL33.glDeleteSamplers;
import static org.lwjgl.opengl.GL33.glGenSamplers;
import static org.lwjgl.opengl.GL33.glSamplerParameteri;

import dev.fopwoc.julia.probe.adapter.ResourceReadback;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import org.lwjgl.system.MemoryStack;

/** Distinct textures and filters prove all sampled resources survive indirect graphics bindings. */
final class ManySamplerChecks {
    private static final String VERTEX =
            "#version 430 core\nvoid main(){vec2 p=vec2((gl_VertexID<<1)&2,gl_VertexID&2);gl_Position=vec4(p*2.-1.,0,1);}";

    private ManySamplerChecks() {}

    static void run(Path output) throws IOException {
        Files.deleteIfExists(output.resolve("many-samplers.txt"));
        int[] textures = new int[32];
        int target = glGenTextures(), fbo = glGenFramebuffers(), clearing = glGenFramebuffers();
        try (var stack = MemoryStack.stackPush()) {
            glPixelStorei(GL_UNPACK_ALIGNMENT, 4);
            for (int i = 0; i < textures.length; i++) {
                textures[i] = glGenTextures();
                glActiveTexture(GL_TEXTURE0 + i);
                glBindTexture(GL_TEXTURE_2D, textures[i]);
                glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, i % 2 == 0 ? GL_LINEAR : GL_NEAREST);
                glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, i % 2 == 0 ? GL_LINEAR : GL_NEAREST);
                var data = stack.bytes(
                        (byte) (i * 4 + 2),
                        (byte) 0,
                        (byte) 0,
                        (byte) 255,
                        (byte) (i * 4 + 100),
                        (byte) 0,
                        (byte) 0,
                        (byte) 255);
                glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, 2, 1, 0, GL_RGBA, GL_UNSIGNED_BYTE, data);
            }
            glActiveTexture(GL_TEXTURE0);
            glBindTexture(GL_TEXTURE_2D, target);
            glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, 32, 1, 0, GL_RGBA, GL_UNSIGNED_BYTE, (ByteBuffer) null);
            glBindTexture(GL_TEXTURE_2D, textures[0]);
            glBindFramebuffer(GL_FRAMEBUFFER, fbo);
            glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, target, 0);
            glViewport(0, 0, 32, 1);
            glDisable(GL_BLEND);
            glDisable(GL_DEPTH_TEST);
            glDisable(GL_CULL_FACE);
            glDisable(GL_SCISSOR_TEST);
            glColorMask(true, true, true, true);
            int verified = 0;
            // Include a direct stage before and after indirect stages to expose stale native bindings.
            for (int count : new int[] {2, 17, 20, 26, 32, 2}) {
                var source = new StringBuilder("#version 430 core\nout vec4 color;\n");
                for (int i = 0; i < count; i++)
                    source.append("uniform sampler2D image").append(i).append(";\n");
                source.append(
                        "uniform sampler2D retired[12];vec4 unreachable(){return texture(retired[11],vec2(.5));}\n");
                source.append("void main(){int i=int(gl_FragCoord.x);color=vec4(0);");
                for (int i = 0; i < count; i++)
                    source.append("if(i==")
                            .append(i)
                            .append(")color=texture(image")
                            .append(i)
                            .append(",vec2(0.5));");
                source.append("}");
                int program = program(VERTEX, source.toString());
                try {
                    glUseProgram(program);
                    if (glGetUniformLocation(program, "retired[0]") != -1)
                        throw new IllegalStateException("Unreachable sampler array became active");
                    for (int i = 0; i < count; i++) glUniform1i(glGetUniformLocation(program, "image" + i), i);
                    glDrawArrays(GL_TRIANGLES, 0, 3);
                    int[] expected = new int[count];
                    for (int i = 0; i < count; i++) expected[i] = i;
                    verify(target, expected);
                    verified += count;
                } finally {
                    glUseProgram(0);
                    glDeleteProgram(program);
                }
            }
            int array = program(VERTEX, """
                    #version 430 core
                    uniform sampler2D images[16];uniform sampler2D beforeImage;uniform sampler2D afterImage;
                    uniform int selected;out vec4 color;
                    void main(){if(selected<16)color=texture(images[selected],vec2(0.5));
                      else if(selected==16)color=texture(beforeImage,vec2(0.5));else color=texture(afterImage,vec2(0.5));}
                    """);
            try {
                glUseProgram(array);
                int location = glGetUniformLocation(array, "images");
                if (location < 0 || location != glGetUniformLocation(array, "images[0]"))
                    throw new IllegalStateException("Sampler array base alias is missing");
                var units = stack.mallocInt(18);
                units.put(0, -99);
                units.put(17, -98);
                units.position(1).limit(17);
                for (int i = 0; i < 16; i++) units.put(i + 1, 15 - i);
                glUniform1iv(location, units);
                if (units.position() != 1 || units.limit() != 17 || units.get(0) != -99)
                    throw new IllegalStateException("Sampler upload consumed client state");
                for (int i = 0; i < 16; i++)
                    if (glGetUniformLocation(array, "images[" + i + "]") != location + i)
                        throw new IllegalStateException("Sampler array locations are not contiguous");
                glUniform1i(glGetUniformLocation(array, "beforeImage"), 16);
                glUniform1i(glGetUniformLocation(array, "afterImage"), 17);
                int selected = glGetUniformLocation(array, "selected");
                int[] expected = new int[18];
                for (int i = 0; i < 18; i++) expected[i] = i < 16 ? 15 - i : i;
                drawArray(selected, expected.length);
                verify(target, expected);
                verified += expected.length;
                glUniform1iv(glGetUniformLocation(array, "images[8]"), new int[] {24, 25, 26});
                expected[8] = 24;
                expected[9] = 25;
                expected[10] = 26;
                try {
                    glUniform1iv(glGetUniformLocation(array, "images[15]"), new int[] {31, 30});
                    throw new IllegalStateException("Oversized sampler upload accepted");
                } catch (IllegalArgumentException accepted) {
                }
                drawArray(selected, expected.length);
                verify(target, expected);
                verified += expected.length;
                // A queued argument table must retain the old sampling state when the next draw rebinds it.
                glEnable(GL_SCISSOR_TEST);
                glScissor(0, 0, 1, 1);
                glUniform1i(selected, 0);
                glDrawArrays(GL_TRIANGLES, 0, 3);
                glUniform1i(location, 31);
                glScissor(1, 0, 1, 1);
                glDrawArrays(GL_TRIANGLES, 0, 3);
                glDisable(GL_SCISSOR_TEST);
                expected[0] = 15;
                expected[1] = 31;
                verify(target, expected);
                verified += expected.length;
                int sampler = glGenSamplers();
                glSamplerParameteri(sampler, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
                glSamplerParameteri(sampler, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
                glEnable(GL_SCISSOR_TEST);
                glUniform1i(selected, 0);
                glBindSampler(31, sampler);
                glScissor(2, 0, 1, 1);
                glDrawArrays(GL_TRIANGLES, 0, 3);
                glBindSampler(31, 0);
                glDeleteSamplers(sampler);
                glScissor(3, 0, 1, 1);
                glDrawArrays(GL_TRIANGLES, 0, 3);
                glActiveTexture(GL_TEXTURE31);
                glBindTexture(GL_TEXTURE_2D, textures[30]);
                glDeleteTextures(textures[31]);
                textures[31] = 0;
                glScissor(4, 0, 1, 1);
                glDrawArrays(GL_TRIANGLES, 0, 3);
                glDisable(GL_SCISSOR_TEST);
                var retained = ResourceReadback.layer(target, 0);
                int[] colors = {31 * 4 + 51, red(31), red(30)};
                for (int i = 0; i < colors.length; i++)
                    if (Math.abs((retained[(i + 2) * 4] & 255) - colors[i]) > 1)
                        throw new IllegalStateException("Queued argument resources did not survive deletion/rebinding");
                verified += colors.length;
            } finally {
                glUseProgram(0);
                glDeleteProgram(array);
            }
            glBindFramebuffer(GL_FRAMEBUFFER, clearing);
            glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, textures[30], 0);
            glClearColor(1, 0, 0, 1);
            glClear(GL_COLOR_BUFFER_BIT);
            glBindFramebuffer(GL_FRAMEBUFFER, fbo);
            // The vertex argument buffer must coexist with the fragment argument buffer and uniform buffers.
            var vertex = new StringBuilder("#version 430 core\nout vec4 value;\n");
            for (int i = 0; i < 32; i++)
                vertex.append("uniform sampler2D v").append(i).append(";\n");
            vertex.append(
                    "void main(){vec2 p=vec2((gl_VertexID<<1)&2,gl_VertexID&2);gl_Position=vec4(p*2.-1.,0,1);value=vec4(0);");
            for (int i = 0; i < 32; i++)
                vertex.append("value+=textureLod(v").append(i).append(",vec2(0.5),0.)/32.;");
            vertex.append("}");
            var combinedFragment = new StringBuilder("#version 430 core\nin vec4 value;out vec4 color;\n");
            for (int i = 0; i < 17; i++)
                combinedFragment.append("uniform sampler2D f").append(i).append(";\n");
            combinedFragment.append("void main(){int i=int(gl_FragCoord.x)%17;vec4 sampled=vec4(0);");
            for (int i = 0; i < 17; i++)
                combinedFragment
                        .append("if(i==")
                        .append(i)
                        .append(")sampled=texture(f")
                        .append(i)
                        .append(",vec2(0.5));");
            combinedFragment.append("color=vec4(value.r,sampled.r,0,1);}");
            int vertexProgram = program(vertex.toString(), combinedFragment.toString());
            try {
                glUseProgram(vertexProgram);
                for (int i = 0; i < 32; i++) glUniform1i(glGetUniformLocation(vertexProgram, "v" + i), i);
                for (int i = 0; i < 17; i++) glUniform1i(glGetUniformLocation(vertexProgram, "f" + i), i);
                glDrawArrays(GL_TRIANGLES, 0, 3);
                var pixels = ResourceReadback.layer(target, 0);
                int sum = 0;
                for (int i = 0; i < 32; i++) sum += i >= 30 ? 255 : red(i);
                int expected = (int) Math.round(sum / 32.0);
                for (int i = 0; i < 32; i++)
                    if (Math.abs((pixels[i * 4] & 255) - expected) > 1
                            || Math.abs((pixels[i * 4 + 1] & 255) - red(i % 17)) > 1)
                        throw new IllegalStateException("Vertex argument bindings failed");
                verified += 32;
            } finally {
                glUseProgram(0);
                glDeleteProgram(vertexProgram);
            }
            Files.writeString(
                    output.resolve("many-samplers.txt"),
                    "PASS\nverified_pixels=" + verified
                            + "\nchecks=unreachable sampler arrays exceed declared budget,26 and 32 live slots,17/20/32 scalar samplers,mixed nearest/linear filtering,direct/indirect switches,16-element array plus scalar samplers,array locations,bulk and partial uploads,invalid array bounds,client positions,queued argument snapshots,sampler object overrides,deleted sampler/texture lifetime,pending clears above slot 16,simultaneous 32 vertex and 17 fragment samplers\n");
        } finally {
            glUseProgram(0);
            glDisable(GL_SCISSOR_TEST);
            glBindFramebuffer(GL_FRAMEBUFFER, 0);
            glDeleteFramebuffers(fbo);
            glDeleteFramebuffers(clearing);
            glDeleteTextures(target);
            glDeleteTextures(textures);
            glActiveTexture(GL_TEXTURE0);
        }
    }

    private static void drawArray(int selected, int count) {
        glEnable(GL_SCISSOR_TEST);
        for (int i = 0; i < count; i++) {
            glUniform1i(selected, i);
            glScissor(i, 0, 1, 1);
            glDrawArrays(GL_TRIANGLES, 0, 3);
        }
        glDisable(GL_SCISSOR_TEST);
    }

    private static int red(int texture) {
        return texture * 4 + (texture % 2 == 0 ? 51 : 100);
    }

    private static void verify(int target, int[] expected) {
        var pixels = ResourceReadback.layer(target, 0);
        for (int i = 0; i < expected.length; i++)
            if (Math.abs((pixels[i * 4] & 255) - red(expected[i])) > 1
                    || pixels[i * 4 + 1] != 0
                    || pixels[i * 4 + 2] != 0
                    || (pixels[i * 4 + 3] & 255) != 255)
                throw new IllegalStateException("Sampled resource mismatch at " + i + ": got " + (pixels[i * 4] & 255)
                        + " expected " + red(expected[i]));
    }

    private static int program(String vertex, String fragment) {
        int vs = glCreateShader(GL_VERTEX_SHADER), fs = glCreateShader(GL_FRAGMENT_SHADER), program = glCreateProgram();
        try {
            glShaderSource(vs, vertex);
            glShaderSource(fs, fragment);
            for (int shader : new int[] {vs, fs}) {
                glCompileShader(shader);
                if (glGetShaderi(shader, GL_COMPILE_STATUS) == GL_FALSE)
                    throw new IllegalStateException(glGetShaderInfoLog(shader));
                glAttachShader(program, shader);
            }
            glLinkProgram(program);
            if (glGetProgrami(program, GL_LINK_STATUS) == GL_FALSE)
                throw new IllegalStateException(glGetProgramInfoLog(program));
            return program;
        } finally {
            glDeleteShader(vs);
            glDeleteShader(fs);
        }
    }
}
