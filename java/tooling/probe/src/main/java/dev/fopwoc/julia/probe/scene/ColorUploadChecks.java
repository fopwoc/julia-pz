package dev.fopwoc.julia.probe.scene;

import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL12.*;
import static org.lwjgl.opengl.GL20.*;
import static org.lwjgl.opengl.GL30.*;
import static org.lwjgl.opengl.GL45.*;

import dev.fopwoc.julia.probe.adapter.ResourceReadback;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import org.lwjgl.system.MemoryUtil;

/** RGB atmosphere/lighting inputs retain their numeric components across physical storage promotion. */
final class ColorUploadChecks {
    private ColorUploadChecks() {}

    static void run(Path output) throws IOException {
        int vertex = shader(GL_VERTEX_SHADER, """
                #version 330 core
                void main(){vec2 p=vec2((gl_VertexID<<1)&2,gl_VertexID&2);gl_Position=vec4(p*2.-1.,0,1);}
                """);
        int fragment = shader(GL_FRAGMENT_SHADER, """
                #version 330 core
                uniform sampler2D source;
                uniform float blueScale;
                out vec4 color;
                void main(){color=texelFetch(source,ivec2(gl_FragCoord.xy),0);color.b*=blueScale;}
                """);
        int program = glCreateProgram(), fbo = glGenFramebuffers();
        glAttachShader(program, vertex);
        glAttachShader(program, fragment);
        glLinkProgram(program);
        if (glGetProgrami(program, GL_LINK_STATUS) == GL_FALSE)
            throw new IllegalStateException(glGetProgramInfoLog(program));
        int alignment = glGetInteger(GL_UNPACK_ALIGNMENT);
        int pixels = 0;
        try {
            glActiveTexture(GL_TEXTURE0);
            glUseProgram(program);
            glUniform1i(glGetUniformLocation(program, "source"), 0);
            glDisable(GL_BLEND);
            glDisable(GL_DEPTH_TEST);
            glDisable(GL_SCISSOR_TEST);
            glDisable(GL_CULL_FACE);
            glPixelStorei(GL_UNPACK_ALIGNMENT, 4);
            pixels += check(program, fbo, GL_RGB16F, 96, 40, false, false);
            for (int format : new int[] {GL_RGB16F, GL_RGB32F, GL_RGBA16F, GL_RGBA8}) {
                pixels += check(program, fbo, format, 4, 2, false, false);
                pixels += check(program, fbo, format, 4, 2, true, false);
            }
            pixels += check(program, fbo, GL_RGB8, 3, 2, false, true);
            pixels += check(program, fbo, GL_RGB8, 3, 2, true, true);
            Files.writeString(
                    output.resolve("color-uploads.txt"),
                    "PASS\nverified_pixels=" + pixels
                            + "\nchecks=96x40 RGB16F atmosphere,float RGB to half/full-float and normalized storage,RGB row padding,bound and named uploads,subimages,preserved source bounds\n");
        } finally {
            glUseProgram(0);
            glBindFramebuffer(GL_FRAMEBUFFER, 0);
            glPixelStorei(GL_UNPACK_ALIGNMENT, alignment);
            glDeleteFramebuffers(fbo);
            glDeleteProgram(program);
            glDeleteShader(vertex);
            glDeleteShader(fragment);
        }
    }

    private static int check(int program, int fbo, int format, int width, int height, boolean named, boolean bytes) {
        int texture = named ? glCreateTextures(GL_TEXTURE_2D) : glGenTextures();
        int target = glGenTextures();
        int row = bytes ? (width * 3 + 3) / 4 * 4 : width * 12;
        var data = MemoryUtil.memAlloc(row * (height - 1) + width * (bytes ? 3 : 12))
                .order(ByteOrder.nativeOrder());
        boolean hdr = format != GL_RGBA8 && format != GL_RGB8;
        try {
            for (int y = 0; y < height; y++)
                for (int x = 0; x < width; x++) {
                    int at = y * row + x * (bytes ? 3 : 12);
                    if (bytes) data.put(at, (byte) 51).put(at + 1, (byte) 102).put(at + 2, (byte) 153);
                    else
                        data.putFloat(at, (x % 4) * .25f)
                                .putFloat(at + 4, (y % 4) * .25f)
                                .putFloat(at + 8, 4f);
                }
            int type = bytes ? GL_UNSIGNED_BYTE : GL_FLOAT;
            if (named) {
                glTextureStorage2D(texture, 1, format, width, height);
                glTextureSubImage2D(texture, 0, 0, 0, width, height, GL_RGB, type, data);
            } else {
                glBindTexture(GL_TEXTURE_2D, texture);
                glTexImage2D(GL_TEXTURE_2D, 0, format, width, height, 0, GL_RGB, type, data);
                glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, width, height, GL_RGB, type, data);
            }
            if (data.position() != 0 || data.limit() != data.capacity())
                throw new IllegalStateException("Color upload consumed source");
            glBindTexture(GL_TEXTURE_2D, target);
            glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, width, height, 0, GL_RGBA, GL_UNSIGNED_BYTE, (ByteBuffer) null);
            glBindFramebuffer(GL_FRAMEBUFFER, fbo);
            glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, target, 0);
            glViewport(0, 0, width, height);
            glBindTexture(GL_TEXTURE_2D, texture);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
            if (glGetTexLevelParameteri(GL_TEXTURE_2D, 0, GL_TEXTURE_INTERNAL_FORMAT) != format)
                throw new IllegalStateException("Color storage identity changed");
            glUniform1f(glGetUniformLocation(program, "blueScale"), bytes ? 1 : .125f);
            glDrawArrays(GL_TRIANGLES, 0, 3);
            var result = ResourceReadback.layer(target, 0);
            for (int y = 0; y < height; y++)
                for (int x = 0; x < width; x++) {
                    int[] expected = bytes
                            ? new int[] {51, 102, 153, 255}
                            : new int[] {
                                Math.round((x % 4) * .25f * 255), Math.round((y % 4) * .25f * 255), hdr ? 128 : 32, 255
                            };
                    for (int c = 0; c < 4; c++) {
                        int got = result[(y * width + x) * 4 + c] & 255;
                        if (Math.abs(got - expected[c]) > 1)
                            throw new IllegalStateException(
                                    "Color upload format=" + format + " named=" + named + " pixel=" + x + "," + y
                                            + " channel=" + c + " got=" + got + " expected=" + expected[c]);
                    }
                }
            return width * height;
        } finally {
            glBindFramebuffer(GL_FRAMEBUFFER, 0);
            glDeleteTextures(texture);
            glDeleteTextures(target);
            MemoryUtil.memFree(data);
        }
    }

    private static int shader(int type, String source) {
        int shader = glCreateShader(type);
        glShaderSource(shader, source);
        glCompileShader(shader);
        if (glGetShaderi(shader, GL_COMPILE_STATUS) == GL_FALSE)
            throw new IllegalStateException(glGetShaderInfoLog(shader));
        return shader;
    }
}
