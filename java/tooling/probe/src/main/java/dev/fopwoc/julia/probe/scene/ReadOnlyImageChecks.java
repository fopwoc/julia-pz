package dev.fopwoc.julia.probe.scene;

import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL13.GL_TEXTURE0;
import static org.lwjgl.opengl.GL13.glActiveTexture;
import static org.lwjgl.opengl.GL20.*;
import static org.lwjgl.opengl.GL30.*;
import static org.lwjgl.opengl.GL42.*;
import static org.lwjgl.opengl.GL43.*;

import dev.fopwoc.julia.probe.adapter.ResourceReadback;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** A compute-written float image survives readonly loads in both compute and fragment stages. */
final class ReadOnlyImageChecks {
    private ReadOnlyImageChecks() {}

    static void run(Path output) throws IOException {
        int source = glGenTextures(), transformed = glGenTextures(), target = glGenTextures();
        int fbo = glGenFramebuffers(), vao = glGenVertexArrays();
        int writer = 0, reader = 0, display = 0;
        try {
            glActiveTexture(GL_TEXTURE0);
            for (int texture : new int[] {source, transformed}) {
                glBindTexture(GL_TEXTURE_2D, texture);
                glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA32F, 8, 4, 0, GL_RGBA, GL_FLOAT, 0L);
            }
            glBindTexture(GL_TEXTURE_2D, target);
            glTexStorage2D(GL_TEXTURE_2D, 1, GL_RGBA8, 8, 4);
            writer = program(GL_COMPUTE_SHADER, """
                    #version 430 core
                    layout(local_size_x=4,local_size_y=2) in;
                    layout(rgba32f,binding=2) uniform writeonly image2D source;
                    void main(){imageStore(source,ivec2(gl_GlobalInvocationID.xy),vec4(-2,.125,4,.75));}
                    """);
            reader = program(GL_COMPUTE_SHADER, """
                    #version 430 core
                    layout(local_size_x=4,local_size_y=2) in;
                    layout(rgba32f,binding=2) uniform readonly image2D source;
                    layout(rgba32f,binding=5) uniform writeonly image2D destination;
                    void main(){
                        ivec2 p=ivec2(gl_GlobalInvocationID.xy);
                        if(any(greaterThanEqual(p,imageSize(source))))return;
                        vec4 value=imageLoad(source,p);
                        imageStore(destination,p,vec4(value.r/-8.,value.g*4.,value.b*.25,value.a));
                    }
                    """);
            display = program(GL_FRAGMENT_SHADER, """
                    #version 430 core
                    layout(rgba32f,binding=5) uniform readonly image2D source;
                    out vec4 color;
                    void main(){
                        ivec2 size=imageSize(source);
                        color=size==ivec2(8,4)?imageLoad(source,ivec2(gl_FragCoord.xy)):vec4(0);
                    }
                    """);
            glBindImageTexture(2, source, 0, false, 0, GL_WRITE_ONLY, GL_RGBA32F);
            glUseProgram(writer);
            glDispatchCompute(2, 2, 1);
            glMemoryBarrier(GL_SHADER_IMAGE_ACCESS_BARRIER_BIT);
            glBindImageTexture(2, source, 0, false, 0, GL_READ_ONLY, GL_RGBA32F);
            glBindImageTexture(5, transformed, 0, false, 0, GL_WRITE_ONLY, GL_RGBA32F);
            glUseProgram(reader);
            glDispatchCompute(2, 2, 1);
            glMemoryBarrier(GL_SHADER_IMAGE_ACCESS_BARRIER_BIT | GL_TEXTURE_FETCH_BARRIER_BIT);
            glBindImageTexture(5, transformed, 0, false, 0, GL_READ_ONLY, GL_RGBA32F);
            glBindFramebuffer(GL_FRAMEBUFFER, fbo);
            glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, target, 0);
            glBindVertexArray(vao);
            glViewport(0, 0, 8, 4);
            glDisable(GL_BLEND);
            glDisable(GL_DEPTH_TEST);
            glDisable(GL_STENCIL_TEST);
            glDisable(GL_SCISSOR_TEST);
            glDisable(GL_CULL_FACE);
            glDisable(GL_ALPHA_TEST);
            glColorMask(true, true, true, true);
            glUseProgram(display);
            glDrawArrays(GL_TRIANGLES, 0, 3);
            byte[] pixels = ResourceReadback.layer(target, 0);
            int[] expected = {64, 128, 255, 191};
            for (int i = 0; i < pixels.length; i++)
                if (Math.abs((pixels[i] & 255) - expected[i % 4]) > 1)
                    throw new IllegalStateException(
                            "Readonly float image changed byte " + i + ": " + (pixels[i] & 255));
            Files.writeString(
                    output.resolve("readonly-images.txt"),
                    "PASS\nverified_pixels=32\nchecks=GPU float writes,negative and fractional values,compute readonly loads,fragment readonly loads,image sizes,sparse image units\n");
        } finally {
            glUseProgram(0);
            glBindFramebuffer(GL_FRAMEBUFFER, 0);
            glBindVertexArray(0);
            glBindImageTexture(2, 0, 0, false, 0, GL_READ_ONLY, GL_RGBA32F);
            glBindImageTexture(5, 0, 0, false, 0, GL_READ_ONLY, GL_RGBA32F);
            for (int program : new int[] {writer, reader, display}) if (program != 0) glDeleteProgram(program);
            glDeleteFramebuffers(fbo);
            glDeleteVertexArrays(vao);
            for (int texture : new int[] {source, transformed, target}) glDeleteTextures(texture);
        }
    }

    private static int program(int stage, String source) {
        int program = glCreateProgram();
        try {
            attach(program, stage, source);
            if (stage == GL_FRAGMENT_SHADER) attach(program, GL_VERTEX_SHADER, """
                    #version 430 core
                    void main(){vec2 p=vec2((gl_VertexID<<1)&2,gl_VertexID&2);gl_Position=vec4(p*2.-1.,0,1);}
                    """);
            glLinkProgram(program);
            if (glGetProgrami(program, GL_LINK_STATUS) == GL_FALSE)
                throw new IllegalStateException(glGetProgramInfoLog(program));
            return program;
        } catch (RuntimeException | Error failure) {
            glDeleteProgram(program);
            throw failure;
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
