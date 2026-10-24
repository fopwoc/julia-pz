package dev.fopwoc.julia.probe.scene;

import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL12.*;
import static org.lwjgl.opengl.GL13.*;
import static org.lwjgl.opengl.GL20.*;
import static org.lwjgl.opengl.GL30.*;

import dev.fopwoc.julia.probe.adapter.ResourceReadback;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.lwjgl.system.MemoryStack;

/** Non-cubic volume mips shrink depth and clamp narrow axes to one texel. */
final class VolumeMipChecks {
    private VolumeMipChecks() {}

    static void run(Path output) throws IOException {
        int volume = glGenTextures(), target = glGenTextures(), fbo = glGenFramebuffers(), program = program();
        try (var stack = MemoryStack.stackPush()) {
            var data = stack.malloc(4 * 2 * 8 * 4);
            for (int z = 0; z < 8; z++)
                for (int i = 0; i < 8; i++)
                    data.put((byte) (16 + z * 32))
                            .put((byte) 64)
                            .put((byte) 200)
                            .put((byte) 255);
            data.flip();
            glActiveTexture(GL_TEXTURE0);
            glBindTexture(GL_TEXTURE_3D, volume);
            glTexParameteri(GL_TEXTURE_3D, GL_TEXTURE_MIN_FILTER, GL_NEAREST_MIPMAP_NEAREST);
            glTexParameteri(GL_TEXTURE_3D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
            glTexParameteri(GL_TEXTURE_3D, GL_TEXTURE_WRAP_R, GL_CLAMP_TO_EDGE);
            glTexImage3D(GL_TEXTURE_3D, 0, GL_RGBA8, 4, 2, 8, 0, GL_RGBA, GL_UNSIGNED_BYTE, data);
            glGenerateMipmap(GL_TEXTURE_3D);
            glBindTexture(GL_TEXTURE_2D, target);
            glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, 8, 4, 0, GL_RGBA, GL_UNSIGNED_BYTE, 0L);
            glBindFramebuffer(GL_FRAMEBUFFER, fbo);
            glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, target, 0);
            glViewport(0, 0, 8, 4);
            glDisable(GL_DEPTH_TEST);
            glDisable(GL_BLEND);
            glDisable(GL_SCISSOR_TEST);
            glDisable(GL_CULL_FACE);
            glUseProgram(program);
            int location = glGetUniformLocation(program, "volume");
            if (location < 0) throw new IllegalStateException("Volume sampler lost its GL uniform location");
            glUniform1i(location, 0);
            glDrawArrays(GL_TRIANGLES, 0, 3);
            byte[] pixels = ResourceReadback.layer(target, 0);
            for (int level = 0; level < 4; level++)
                for (int x = 0; x < 8; x++) {
                    int depth = Math.max(1, 8 >> level);
                    int red = (16 << level) + Math.min(x, depth - 1) * (32 << level);
                    int[] expected = {red, 64, 200, 255};
                    for (int c = 0; c < 4; c++) {
                        int actual = pixels[(level * 8 + x) * 4 + c] & 255;
                        if (Math.abs(actual - expected[c]) > 1)
                            throw new IllegalStateException("Volume mip " + level + " slice " + x + " channel " + c
                                    + ": got " + actual + " expected " + expected[c]);
                    }
                }
            Files.writeString(
                    output.resolve("volume-mips.txt"),
                    "PASS\nchecks=4x2x8 volume,all mip depths,narrow axes,volume colour filtering\n");
        } finally {
            glUseProgram(0);
            glBindFramebuffer(GL_FRAMEBUFFER, 0);
            glDeleteFramebuffers(fbo);
            glDeleteProgram(program);
            glDeleteTextures(volume);
            glDeleteTextures(target);
        }
    }

    private static int program() {
        int program = glCreateProgram();
        for (int type : new int[] {GL_VERTEX_SHADER, GL_FRAGMENT_SHADER}) {
            int shader = glCreateShader(type);
            try {
                glShaderSource(
                        shader,
                        type == GL_VERTEX_SHADER
                                ? "#version 430 core\nvoid main(){vec2 p=vec2((gl_VertexID<<1)&2,gl_VertexID&2);gl_Position=vec4(p*2.-1.,0,1);}"
                                : "#version 430 core\nuniform sampler3D volume;out vec4 colour;void main(){int l=int(gl_FragCoord.y);float d=float(max(1,8>>l));colour=textureLod(volume,vec3(.5,.5,min((floor(gl_FragCoord.x)+.5)/d,1.)),float(l));}");
                glCompileShader(shader);
                if (glGetShaderi(shader, GL_COMPILE_STATUS) == 0)
                    throw new IllegalStateException(glGetShaderInfoLog(shader));
                glAttachShader(program, shader);
            } finally {
                glDeleteShader(shader);
            }
        }
        glLinkProgram(program);
        if (glGetProgrami(program, GL_LINK_STATUS) == 0) throw new IllegalStateException(glGetProgramInfoLog(program));
        return program;
    }
}
