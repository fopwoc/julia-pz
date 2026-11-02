package dev.fopwoc.julia.probe.scene;

import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL15.*;
import static org.lwjgl.opengl.GL20.*;
import static org.lwjgl.opengl.GL30.*;
import static org.lwjgl.opengl.GL32.GL_GEOMETRY_SHADER;
import static org.lwjgl.opengl.GL43.*;

import dev.fopwoc.julia.probe.adapter.ResourceReadback;
import java.nio.ByteBuffer;
import org.lwjgl.system.MemoryUtil;

/** GPU-written indirect commands with real instanced vertex fetching and geometry-stage amplification. */
public final class GeometryIndirectScene {
    private GeometryIndirectScene() {}

    private static final String VERTEX = """
            #version 460 core
            layout(location=0) in float shade;
            flat out float red;
            flat out ivec2 ids;
            void main() {
                vec2 positions[6]=vec2[](vec2(-1,-1),vec2(1,-1),vec2(1,1),vec2(-1,-1),vec2(1,1),vec2(-1,1));
                int slot=gl_DrawID*3+gl_InstanceID;
                vec2 center=vec2(float(slot%3)*2.0/3.0-2.0/3.0,float(slot/3)-0.5);
                gl_Position=vec4(positions[gl_VertexID%6]*vec2(1.0/3.0,0.5)+center,0,1);
                red=shade/255.0;
                ids=ivec2(gl_InstanceID+gl_BaseInstance+gl_DrawID*16,gl_VertexID);
            }
            """;
    private static final String GEOMETRY = """
            #version 460 core
            layout(triangles) in;
            layout(triangle_strip,max_vertices=3) out;
            flat in float red[];
            flat in ivec2 ids[];
            flat out vec3 rgb;
            void main() {
                for(int i=0;i<3;i++) {
                    gl_Position=gl_in[i].gl_Position;
                    rgb=vec3(red[i],float(ids[i].x)/255.0,float(ids[i].y+gl_PrimitiveIDIn*10)/255.0);
                    EmitVertex();
                }
                EndPrimitive();
            }
            """;

    public static void run() {
        int program = program(
                new int[] {GL_VERTEX_SHADER, GL_GEOMETRY_SHADER, GL_FRAGMENT_SHADER},
                new String[] {VERTEX, GEOMETRY, """
                #version 460 core
                flat in vec3 rgb;
                out vec4 color;
                void main() {color=vec4(rgb,1);}
                """});
        int writer = program(new int[] {GL_COMPUTE_SHADER}, new String[] {"""
                #version 460 core
                layout(local_size_x=1) in;
                layout(std430,binding=12) buffer Commands {uint words[];};
                void main() {
                    if(words[5]==0u) {words[5]=3u;words[13]=3u;}
                    else {words[7]=3u;words[15]=6u;}
                }
                """});
        int texture = glGenTextures(),
                fbo = glGenFramebuffers(),
                vao = glGenVertexArrays(),
                vbo = glGenBuffers(),
                commands = glGenBuffers();
        var data = MemoryUtil.memCalloc(96);
        var shades = MemoryUtil.memAllocFloat(8);
        try {
            for (int i = 0; i < 8; i++) shades.put(i, i * 16);
            data.putInt(16, 6).putInt(24, 6).putInt(28, 2);
            data.putInt(48, 6).putInt(56, 12).putInt(60, 5);
            glBindTexture(GL_TEXTURE_2D, texture);
            glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, 24, 16, 0, GL_RGBA, GL_UNSIGNED_BYTE, (ByteBuffer) null);
            glBindFramebuffer(GL_FRAMEBUFFER, fbo);
            glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, texture, 0);
            require(glCheckFramebufferStatus(GL_FRAMEBUFFER) == GL_FRAMEBUFFER_COMPLETE, "framebuffer");
            glBindVertexArray(vao);
            glBindBuffer(GL_ARRAY_BUFFER, vbo);
            glBufferData(GL_ARRAY_BUFFER, shades, GL_STATIC_DRAW);
            glVertexAttribPointer(0, 1, GL_FLOAT, false, 4, 0L);
            glEnableVertexAttribArray(0);
            glVertexAttribDivisor(0, 2);
            glBindBuffer(GL_DRAW_INDIRECT_BUFFER, commands);
            glBufferData(GL_DRAW_INDIRECT_BUFFER, data, GL_DYNAMIC_DRAW);
            glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 12, commands);
            glUseProgram(writer);
            glDispatchCompute(1, 1, 1);
            glMemoryBarrier(GL_COMMAND_BARRIER_BIT | GL_BUFFER_UPDATE_BARRIER_BIT | GL_SHADER_STORAGE_BARRIER_BIT);
            glViewport(0, 0, 24, 16);
            glDisable(GL_DEPTH_TEST);
            glDisable(GL_CULL_FACE);
            glDisable(GL_BLEND);
            glDisable(GL_SCISSOR_TEST);
            glDisable(GL_STENCIL_TEST);
            glColorMask(true, true, true, true);
            glClearColor(0, 0, 0, 0);
            glClear(GL_COLOR_BUFFER_BIT);
            glUseProgram(program);
            glMultiDrawArraysIndirect(GL_TRIANGLES, 16L, 3, 32);
            check(texture, true, 0);
            glUseProgram(writer);
            glDispatchCompute(1, 1, 1);
            glMemoryBarrier(GL_COMMAND_BARRIER_BIT | GL_BUFFER_UPDATE_BARRIER_BIT | GL_SHADER_STORAGE_BARRIER_BIT);
            glUseProgram(program);
            glMultiDrawArraysIndirect(GL_TRIANGLES, 16L, 3, 32);
            check(texture, true, 1);
            require(glGetInteger(GL_CURRENT_PROGRAM) == program, "program restoration");
            require(glGetInteger(GL_VERTEX_ARRAY_BINDING) == vao, "VAO restoration");
            require(glGetInteger(GL_DRAW_INDIRECT_BUFFER_BINDING) == commands, "indirect binding restoration");
            glUseProgram(0);
            glClear(GL_COLOR_BUFFER_BIT);
            glUseProgram(program);
            glDrawArraysInstancedBaseInstance(GL_TRIANGLES, 6, 6, 3, 2);
            check(texture, false, 0);
        } finally {
            glUseProgram(0);
            glBindFramebuffer(GL_FRAMEBUFFER, 0);
            glBindVertexArray(0);
            glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 12, 0);
            glBindBuffer(GL_DRAW_INDIRECT_BUFFER, 0);
            glDeleteBuffers(commands);
            glDeleteBuffers(vbo);
            glDeleteTextures(texture);
            glDeleteFramebuffers(fbo);
            glDeleteVertexArrays(vao);
            glDeleteProgram(program);
            glDeleteProgram(writer);
            MemoryUtil.memFree(data);
            MemoryUtil.memFree(shades);
        }
    }

    private static void check(int texture, boolean multi, int bias) {
        var pixels = ResourceReadback.layer(texture, 0);
        for (int draw = 0; draw < (multi ? 2 : 1); draw++)
            for (int instance = 0; instance < 3; instance++) {
                int at = ((draw * 8 + 5) * 24 + instance * 8 + 2) * 4;
                int base = (draw == 0 ? 2 : 5) + bias, first = draw == 0 ? 6 : 12;
                int[] expected = {(base + instance / 2) * 16, instance + base + draw * 16, first + 5 + 10, 255};
                for (int c = 0; c < 4; c++)
                    require(
                            Math.abs((pixels[at + c] & 255) - expected[c]) <= 1,
                            "draw=" + draw + ", instance=" + instance + ", channel=" + c + ", expected=" + expected[c]
                                    + ", got=" + (pixels[at + c] & 255));
            }
    }

    private static int program(int[] types, String[] sources) {
        int program = glCreateProgram();
        try {
            for (int i = 0; i < types.length; i++) {
                int shader = glCreateShader(types[i]);
                try {
                    glShaderSource(shader, sources[i]);
                    glCompileShader(shader);
                    require(glGetShaderi(shader, GL_COMPILE_STATUS) != 0, glGetShaderInfoLog(shader));
                    glAttachShader(program, shader);
                } finally {
                    glDeleteShader(shader);
                }
            }
            glLinkProgram(program);
            require(glGetProgrami(program, GL_LINK_STATUS) != 0, glGetProgramInfoLog(program));
            return program;
        } catch (RuntimeException | Error failure) {
            glDeleteProgram(program);
            throw failure;
        }
    }

    private static void require(boolean valid, String description) {
        if (!valid) throw new IllegalStateException("Geometry indirect integration: " + description);
    }
}
