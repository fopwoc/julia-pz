package dev.fopwoc.julia.probe.scene;

import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL13.*;
import static org.lwjgl.opengl.GL15.*;
import static org.lwjgl.opengl.GL20.*;
import static org.lwjgl.opengl.GL30.*;
import static org.lwjgl.opengl.GL31.*;
import static org.lwjgl.opengl.GL32.*;

import dev.fopwoc.julia.probe.adapter.ResourceReadback;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import org.lwjgl.system.MemoryUtil;

/** Point amplification reads buffer samplers in both pre-raster stages through the public GL program. */
final class GeometryPointScene {
    private GeometryPointScene() {}

    static void run(Path output) throws IOException {
        int program = glCreateProgram(), vao = glGenVertexArrays(), fbo = glGenFramebuffers();
        int target = glGenTextures(), positions = glGenTextures(), tint = glGenTextures();
        int positionBuffer = glGenBuffers(), tintBuffer = glGenBuffers();
        var data = MemoryUtil.memCallocFloat(16);
        try {
            attach(program, GL_VERTEX_SHADER, """
                    #version 460 core
                    uniform samplerBuffer positions;
                    out float red;
                    void main(){vec4 p=texelFetch(positions,gl_VertexID);gl_Position=vec4(p.xy,0,1);red=p.z;}
                    """);
            attach(program, GL_GEOMETRY_SHADER, """
                    #version 460 core
                    layout(points) in;
                    layout(points,max_vertices=2) out;
                    in float red[];
                    out vec4 shade;
                    uniform samplerBuffer tint;
                    uniform float diameter;
                    void main(){
                        for(int i=0;i<2;i++){
                            vec4 c=texelFetch(tint,0);
                            shade=vec4(red[0],c.gb,1);
                            gl_Position=vec4(gl_in[0].gl_Position.x,i==0?-.625:.375,0,1);
                            gl_PointSize=diameter;
                            EmitVertex();EndPrimitive();
                        }
                    }
                    """);
            attach(program, GL_FRAGMENT_SHADER, """
                    #version 460 core
                    in vec4 shade;
                    out vec4 color;
                    void main(){color=shade;}
                    """);
            glLinkProgram(program);
            if (glGetProgrami(program, GL_LINK_STATUS) == GL_FALSE)
                throw new IllegalStateException(glGetProgramInfoLog(program));
            glBindTexture(GL_TEXTURE_2D, target);
            glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, 8, 8, 0, GL_RGBA, GL_UNSIGNED_BYTE, (ByteBuffer) null);
            glBindFramebuffer(GL_FRAMEBUFFER, fbo);
            glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, target, 0);
            data.put(8, -.5f).put(10, .25f).put(12, .5f).put(14, .25f);
            glBindBuffer(GL_TEXTURE_BUFFER, positionBuffer);
            glBufferData(GL_TEXTURE_BUFFER, data, GL_STATIC_DRAW);
            glActiveTexture(GL_TEXTURE3);
            glBindTexture(GL_TEXTURE_BUFFER, positions);
            glTexBuffer(GL_TEXTURE_BUFFER, GL_RGBA32F, positionBuffer);
            data.limit(4);
            data.put(0, 0).put(1, .5f).put(2, .75f).put(3, 1);
            glBindBuffer(GL_TEXTURE_BUFFER, tintBuffer);
            glBufferData(GL_TEXTURE_BUFFER, data, GL_STATIC_DRAW);
            glActiveTexture(GL_TEXTURE7);
            glBindTexture(GL_TEXTURE_BUFFER, tint);
            glTexBuffer(GL_TEXTURE_BUFFER, GL_RGBA32F, tintBuffer);
            glBindVertexArray(vao);
            glViewport(0, 0, 8, 8);
            glDisable(GL_DEPTH_TEST);
            glDisable(GL_BLEND);
            glDisable(GL_CULL_FACE);
            glDisable(GL_STENCIL_TEST);
            glDisable(GL_SCISSOR_TEST);
            glColorMask(true, true, true, true);
            glEnable(GL_PROGRAM_POINT_SIZE);
            glUseProgram(program);
            glUniform1i(glGetUniformLocation(program, "positions"), 3);
            glUniform1i(glGetUniformLocation(program, "tint"), 7);
            for (int size = 1; size <= 2; size++) {
                glClearColor(0, 0, 0, 0);
                glClear(GL_COLOR_BUFFER_BIT);
                glUniform1f(glGetUniformLocation(program, "diameter"), size);
                glDrawArrays(GL_POINTS, 2, 2);
                byte[] pixels = ResourceReadback.layer(target, 0);
                int colored = 0;
                for (int at = 0; at < pixels.length; at += 4) {
                    if ((pixels[at + 3] & 255) == 0) continue;
                    colored++;
                    int[] expected = {64, 128, 191, 255};
                    for (int c = 0; c < 4; c++)
                        if (Math.abs((pixels[at + c] & 255) - expected[c]) > 1)
                            throw new IllegalStateException("Geometry point sampler color differs at byte " + (at + c));
                }
                if (colored != 4 * size * size)
                    throw new IllegalStateException("Geometry point coverage: " + colored + " for size " + size);
            }
            Files.writeString(
                    output.resolve("geometry-points.txt"),
                    "PASS\nverified_pixels=128\nchecks=point amplification,vertex/geometry buffer samplers,nonzero first vertex,point sizes 1/2\n");
        } finally {
            MemoryUtil.memFree(data);
            glUseProgram(0);
            glBindFramebuffer(GL_FRAMEBUFFER, 0);
            glBindVertexArray(0);
            glDisable(GL_PROGRAM_POINT_SIZE);
            glActiveTexture(GL_TEXTURE3);
            glBindTexture(GL_TEXTURE_BUFFER, 0);
            glActiveTexture(GL_TEXTURE7);
            glBindTexture(GL_TEXTURE_BUFFER, 0);
            glActiveTexture(GL_TEXTURE0);
            glBindBuffer(GL_TEXTURE_BUFFER, 0);
            glDeleteProgram(program);
            glDeleteFramebuffers(fbo);
            glDeleteVertexArrays(vao);
            for (int texture : new int[] {target, positions, tint}) glDeleteTextures(texture);
            glDeleteBuffers(positionBuffer);
            glDeleteBuffers(tintBuffer);
        }
    }

    private static void attach(int program, int stage, String source) {
        int shader = glCreateShader(stage);
        try {
            glShaderSource(shader, source);
            glCompileShader(shader);
            if (glGetShaderi(shader, GL_COMPILE_STATUS) == GL_FALSE)
                throw new IllegalStateException(glGetShaderInfoLog(shader));
            glAttachShader(program, shader);
        } finally {
            glDeleteShader(shader);
        }
    }
}
