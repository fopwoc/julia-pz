package dev.fopwoc.julia.probe.scene;

import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL15.*;
import static org.lwjgl.opengl.GL20.*;
import static org.lwjgl.opengl.GL30.*;
import static org.lwjgl.opengl.GL40.GL_DRAW_INDIRECT_BUFFER;
import static org.lwjgl.opengl.GL42.glMemoryBarrier;
import static org.lwjgl.opengl.GL43.*;

import dev.fopwoc.julia.probe.adapter.ResourceReadback;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.lwjgl.system.MemoryStack;

/** GPU-written command records must preserve bases, vertex/instance IDs, stride, and constant attributes. */
final class IndirectDrawChecks {
    private IndirectDrawChecks() {}

    static void run(Path output) throws IOException {
        Files.deleteIfExists(output.resolve("indirect-draws.txt"));
        int vao = glGenVertexArrays(), records = glGenBuffers(), commands = glGenBuffers();
        int target = glGenTextures(), white = glGenTextures(), fbo = glGenFramebuffers();
        int draw = drawProgram(), compute = computeProgram();
        try (var stack = MemoryStack.stackPush()) {
            glBindVertexArray(vao);
            var attributes = stack.callocFloat(12 * 4);
            for (int i = 0; i < 12; i++) {
                attributes.put(i * 4, i >= 3 ? 1 : 0);
                attributes.put(i * 4 + 1, (20 + i * 25) / 255f);
                attributes.put(i * 4 + 2, (10 + i * 20) / 255f);
            }
            // GL storage has no permanent target: allocate as storage, then fetch the same bytes as vertex records.
            glBindBuffer(GL_SHADER_STORAGE_BUFFER, records);
            glBufferData(GL_SHADER_STORAGE_BUFFER, attributes, GL_STATIC_DRAW);
            glBindVertexBuffer(6, records, 0, 16);
            glVertexAttribFormat(2, 4, GL_FLOAT, false, 0);
            glVertexAttribBinding(2, 6);
            glEnableVertexAttribArray(2);
            glVertexBindingDivisor(6, 1);
            glVertexAttrib4f(5, .75f, 0, 0, 1);
            glBindBuffer(GL_DRAW_INDIRECT_BUFFER, commands);
            glBufferData(GL_DRAW_INDIRECT_BUFFER, 128L, GL_DYNAMIC_DRAW);
            glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 3, commands);
            glBindTexture(GL_TEXTURE_2D, white);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
            glTexImage2D(
                    GL_TEXTURE_2D,
                    0,
                    GL_RGBA8,
                    1,
                    1,
                    0,
                    GL_RGBA,
                    GL_UNSIGNED_BYTE,
                    stack.bytes((byte) 255, (byte) 255, (byte) 255, (byte) 255));
            for (int unit : new int[] {96, 127}) {
                glActiveTexture(GL_TEXTURE0 + unit);
                glBindTexture(GL_TEXTURE_2D, white);
            }
            glActiveTexture(GL_TEXTURE0);
            glBindTexture(GL_TEXTURE_2D, target);
            glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, 12, 4, 0, GL_RGBA, GL_UNSIGNED_BYTE, 0L);
            glBindFramebuffer(GL_FRAMEBUFFER, fbo);
            glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, target, 0);
            glViewport(0, 0, 12, 4);
            glDisable(GL_BLEND);
            glDisable(GL_DEPTH_TEST);
            glDisable(GL_SCISSOR_TEST);
            glDisable(GL_CULL_FACE);
            glUseProgram(compute);
            glDispatchCompute(1, 1, 1);
            glMemoryBarrier(GL_COMMAND_BARRIER_BIT | GL_SHADER_STORAGE_BARRIER_BIT);
            glUseProgram(draw);
            glUniform1iv(glGetUniformLocation(draw, "images"), new int[] {96, 127});
            glClearColor(0, 0, 0, 0);
            glClear(GL_COLOR_BUFFER_BIT);
            glMultiDrawArraysIndirect(GL_TRIANGLES, 16L, 4, 24);
            verify(target, false);
            for (long offset : new long[] {-4, 2, 124}) {
                try {
                    glMultiDrawArraysIndirect(GL_TRIANGLES, offset, 4, 24);
                    throw new IllegalStateException("Invalid indirect range accepted");
                } catch (IllegalArgumentException expected) {
                }
            }
            try {
                glMultiDrawArraysIndirect(GL_TRIANGLES, 16L, 4, 3);
                throw new IllegalStateException("Unaligned indirect stride accepted");
            } catch (IllegalArgumentException expected) {
            }
            verify(target, false);
            glClear(GL_COLOR_BUFFER_BIT);
            // The first GPU command is also a valid packed single-command stream.
            glMultiDrawArraysIndirect(GL_TRIANGLES, 16L, 1, 0);
            verify(target, true);
            glMultiDrawArraysIndirect(GL_TRIANGLES, 16L, 0, 16);
            verify(target, true);
            glVertexBindingDivisor(6, 2);
            glUniform1i(glGetUniformLocation(draw, "standalone"), 1);
            glBufferSubData(GL_DRAW_INDIRECT_BUFFER, 16L, stack.ints(6, 3, 6, 2));
            glClear(GL_COLOR_BUFFER_BIT);
            glMultiDrawArraysIndirect(GL_TRIANGLES, 16L, 1, 16);
            var divided = ResourceReadback.layer(target, 0);
            for (int y = 0; y < 4; y++)
                for (int x = 0; x < 12; x++) {
                    int record = 2 + (x / 4) / 2;
                    int[] expected = {20 + record * 25, 10 + record * 20, 204, 191};
                    for (int c = 0; c < 4; c++)
                        if (Math.abs((divided[(y * 12 + x) * 4 + c] & 255) - expected[c]) > 1)
                            throw new IllegalStateException(
                                    "Indirect divisor sample mismatch record=" + record + " channel=" + c + " actual="
                                            + (divided[(y * 12 + x) * 4 + c] & 255) + " expected=" + expected[c]);
                }
            if (glGetInteger(GL_DRAW_INDIRECT_BUFFER_BINDING) != commands)
                throw new IllegalStateException("Indirect draw changed command buffer binding");
            Files.writeString(
                    output.resolve("indirect-draws.txt"),
                    "PASS\nverified_pixels=240\nchecks=GPU-generated commands,nonzero command offset,padded/packed stride,multiple draws and instances,base instances,zero draw/instance counts,vertex/instance IDs,constant attributes,disabled integer attributes,argument-buffer vertex samplers,divisor two,invalid ranges and alignment\n");
        } finally {
            glUseProgram(0);
            glBindVertexArray(0);
            glBindFramebuffer(GL_FRAMEBUFFER, 0);
            glActiveTexture(GL_TEXTURE0);
            glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 3, 0);
            glBindBuffer(GL_DRAW_INDIRECT_BUFFER, 0);
            glDeleteVertexArrays(vao);
            glDeleteBuffers(records);
            glDeleteBuffers(commands);
            glDeleteTextures(target);
            glDeleteTextures(white);
            glDeleteFramebuffers(fbo);
            glDeleteProgram(draw);
            glDeleteProgram(compute);
        }
    }

    private static void verify(int target, boolean single) {
        byte[] pixels = ResourceReadback.layer(target, 0);
        for (int y = 0; y < 4; y++)
            for (int x = 0; x < 12; x++) {
                int cell = x / 4, record = cell == 0 ? 1 : cell + 2;
                int[] expected = single && cell != 0
                        ? new int[4]
                        : new int[] {20 + record * 25, 10 + record * 20, cell == 0 ? 51 : 204, 191};
                for (int c = 0; c < 4; c++)
                    if (Math.abs((pixels[(y * 12 + x) * 4 + c] & 255) - expected[c]) > 1)
                        throw new IllegalStateException("Indirect pixel mismatch x=" + x + " channel=" + c + " actual="
                                + (pixels[(y * 12 + x) * 4 + c] & 255) + " expected=" + expected[c]);
            }
    }

    private static int drawProgram() {
        int p = glCreateProgram(), v = glCreateShader(GL_VERTEX_SHADER), f = glCreateShader(GL_FRAGMENT_SHADER);
        try {
            glShaderSource(
                    v,
                    "#version 430 core\nlayout(location=2)in vec4 record;layout(location=5)in float alpha;layout(location=6)in ivec4 disabledRun;uniform sampler2D images[2];uniform int standalone;uniform int readDisabled;flat out vec4 shade;void main(){vec2 corners[6]=vec2[6](vec2(0,0),vec2(1,0),vec2(1,1),vec2(0,0),vec2(1,1),vec2(0,1));vec2 p=corners[gl_VertexID%6];gl_Position=vec4(((standalone!=0?0.:record.x)+float(gl_InstanceID)+p.x)*2./3.-1.,p.y*2.-1.,0,1);float sampled=(textureLod(images[0],vec2(.5),0).r+textureLod(images[1],vec2(.5),0).r)*.5;shade=vec4(record.yz,gl_VertexID<6?.2:.8,alpha)*sampled;if(readDisabled!=0)shade.x+=float(disabledRun.x);}");
            glShaderSource(f, "#version 430 core\nflat in vec4 shade;out vec4 color;void main(){color=shade;}");
            compile(v);
            compile(f);
            glAttachShader(p, v);
            glAttachShader(p, f);
            glLinkProgram(p);
            if (glGetProgrami(p, GL_LINK_STATUS) == 0) throw new IllegalStateException(glGetProgramInfoLog(p));
            return p;
        } finally {
            glDeleteShader(v);
            glDeleteShader(f);
        }
    }

    private static int computeProgram() {
        int p = glCreateProgram(), s = glCreateShader(GL_COMPUTE_SHADER);
        try {
            glShaderSource(
                    s,
                    "#version 430 core\nlayout(local_size_x=1)in;layout(std430,binding=3)buffer Commands{uint words[];};void main(){for(int i=0;i<32;i++)words[i]=0u;words[4]=6u;words[5]=1u;words[6]=0u;words[7]=1u;words[10]=6u;words[11]=2u;words[12]=6u;words[13]=3u;words[16]=6u;words[17]=0u;words[18]=0u;words[19]=999u;words[22]=0u;words[23]=8u;words[24]=0u;words[25]=999u;}");
            compile(s);
            glAttachShader(p, s);
            glLinkProgram(p);
            if (glGetProgrami(p, GL_LINK_STATUS) == 0) throw new IllegalStateException(glGetProgramInfoLog(p));
            return p;
        } finally {
            glDeleteShader(s);
        }
    }

    private static void compile(int shader) {
        glCompileShader(shader);
        if (glGetShaderi(shader, GL_COMPILE_STATUS) == 0) throw new IllegalStateException(glGetShaderInfoLog(shader));
    }
}
