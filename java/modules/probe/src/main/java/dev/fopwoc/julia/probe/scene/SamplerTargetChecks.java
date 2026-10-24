package dev.fopwoc.julia.probe.scene;

import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL12.*;
import static org.lwjgl.opengl.GL13.*;
import static org.lwjgl.opengl.GL15.*;
import static org.lwjgl.opengl.GL20.*;
import static org.lwjgl.opengl.GL30.*;
import static org.lwjgl.opengl.GL33.*;
import static org.lwjgl.opengl.GL42.*;
import static org.lwjgl.opengl.GL43.*;

import dev.fopwoc.julia.probe.adapter.ResourceReadback;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.lwjgl.system.MemoryStack;

/** Graphics and compute must resolve each sampler's own target, including unbound targets on shared units. */
final class SamplerTargetChecks {
    private static final String READ = """
            uniform sampler2D flatImage;
            uniform sampler3D volume;
            uniform sampler2DArray layers;
            uniform sampler3D emptyVolume;
            uniform sampler2DArray emptyLayers;
            uniform usampler2DArray emptyUnsigned;
            uniform sampler2DShadow emptyShadow;
            uniform sampler2DArrayShadow emptyShadowLayers;
            vec4 readValue(int i) {
                if (i == 0) return textureLod(flatImage, vec2(.5), 0.);
                if (i == 1) return textureLod(volume, vec3(.5), 0.);
                if (i == 2) return textureLod(layers, vec3(.5,.5,0), 0.);
                if (i == 3) return textureLod(emptyVolume, vec3(.5), 0.);
                if (i == 4) return textureLod(emptyLayers, vec3(.5,.5,0), 0.);
                if (i == 5) return vec4(texelFetch(emptyUnsigned, ivec3(0), 0));
                float shadow = i == 6 ? textureLod(emptyShadow, vec3(.5), 0.)
                        : texture(emptyShadowLayers, vec4(.5,.5,0,.5));
                return vec4(vec3(shadow),1);
            }
            """;

    private SamplerTargetChecks() {}

    static void run(Path output) throws IOException {
        int[] textures = {glGenTextures(), glGenTextures(), glGenTextures(), glGenTextures()};
        int[] targets = {GL_TEXTURE_2D, GL_TEXTURE_3D, GL_TEXTURE_2D_ARRAY};
        int fbo = glGenFramebuffers(),
                storage = glGenBuffers(),
                sampler = glGenSamplers(),
                shadowSampler = glGenSamplers();
        int graphics = program(false), compute = program(true);
        try (var stack = MemoryStack.stackPush()) {
            glActiveTexture(GL_TEXTURE5);
            for (int i = 0; i < 3; i++) {
                glBindTexture(targets[i], textures[i]);
                glTexParameteri(targets[i], GL_TEXTURE_MIN_FILTER, GL_NEAREST);
                glTexParameteri(targets[i], GL_TEXTURE_MAG_FILTER, GL_NEAREST);
                var pixel = stack.bytes((byte) (32 << i), (byte) 64, (byte) 200, (byte) 255);
                if (i == 0) glTexImage2D(targets[i], 0, GL_RGBA8, 1, 1, 0, GL_RGBA, GL_UNSIGNED_BYTE, pixel);
                else glTexImage3D(targets[i], 0, GL_RGBA8, 1, 1, 1, 0, GL_RGBA, GL_UNSIGNED_BYTE, pixel);
            }
            for (int unit : new int[] {6, 7}) {
                glActiveTexture(GL_TEXTURE0 + unit);
                for (int target : targets) glBindTexture(target, 0);
                glBindSampler(unit, sampler);
            }
            glSamplerParameteri(sampler, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
            glSamplerParameteri(sampler, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
            glSamplerParameteri(shadowSampler, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
            glSamplerParameteri(shadowSampler, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
            glSamplerParameteri(shadowSampler, GL_TEXTURE_COMPARE_MODE, GL_COMPARE_REF_TO_TEXTURE);
            glSamplerParameteri(shadowSampler, GL_TEXTURE_COMPARE_FUNC, GL_LEQUAL);
            for (int unit : new int[] {8, 9}) {
                glActiveTexture(GL_TEXTURE0 + unit);
                glBindTexture(GL_TEXTURE_2D, 0);
                glBindTexture(GL_TEXTURE_2D_ARRAY, 0);
                glBindSampler(unit, shadowSampler);
            }
            glActiveTexture(GL_TEXTURE0);
            glBindTexture(GL_TEXTURE_2D, textures[3]);
            glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, 8, 1, 0, GL_RGBA, GL_UNSIGNED_BYTE, 0L);
            glBindFramebuffer(GL_FRAMEBUFFER, fbo);
            glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, textures[3], 0);
            glViewport(0, 0, 8, 1);
            glDisable(GL_DEPTH_TEST);
            glDisable(GL_BLEND);
            glDisable(GL_SCISSOR_TEST);
            glDisable(GL_CULL_FACE);
            bind(graphics);
            glDrawArrays(GL_TRIANGLES, 0, 3);
            byte[] pixels = ResourceReadback.layer(textures[3], 0);
            for (int i = 0; i < 8; i++) for (int c = 0; c < 4; c++) verify(i, c, (pixels[i * 4 + c] & 255) / 255f);
            glBindBuffer(GL_SHADER_STORAGE_BUFFER, storage);
            glBufferData(GL_SHADER_STORAGE_BUFFER, 8 * 16, GL_DYNAMIC_COPY);
            glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 0, storage);
            bind(compute);
            glDispatchCompute(8, 1, 1);
            glMemoryBarrier(GL_BUFFER_UPDATE_BARRIER_BIT);
            var values = stack.mallocFloat(32);
            glGetBufferSubData(GL_SHADER_STORAGE_BUFFER, 0, values);
            for (int i = 0; i < 8; i++) for (int c = 0; c < 4; c++) verify(i, c, values.get(i * 4 + c));
            Files.writeString(
                    output.resolve("sampler-targets.txt"),
                    "PASS\nchecks=graphics and compute,shared unit 2D/3D/array,unbound volume/array,unsigned array alpha,unbound 2D/array shadow samplers\n");
        } finally {
            glUseProgram(0);
            glBindFramebuffer(GL_FRAMEBUFFER, 0);
            glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 0, 0);
            glBindSampler(6, 0);
            glBindSampler(7, 0);
            glBindSampler(8, 0);
            glBindSampler(9, 0);
            glDeleteSamplers(shadowSampler);
            glDeleteSamplers(sampler);
            glDeleteBuffers(storage);
            glDeleteFramebuffers(fbo);
            glDeleteProgram(graphics);
            glDeleteProgram(compute);
            for (int texture : textures) glDeleteTextures(texture);
            glActiveTexture(GL_TEXTURE0);
        }
    }

    private static void verify(int pixel, int channel, float actual) {
        float expected = channel == 3
                ? 1
                : pixel >= 3 ? 0 : channel == 0 ? (32 << pixel) / 255f : channel == 1 ? 64 / 255f : 200 / 255f;
        if (!Float.isFinite(actual) || Math.abs(actual - expected) > 1f / 255f)
            throw new IllegalStateException(
                    "Sampler target " + pixel + " channel " + channel + ": got " + actual + " expected " + expected);
    }

    private static void bind(int program) {
        glUseProgram(program);
        String[] names = {
            "flatImage",
            "volume",
            "layers",
            "emptyVolume",
            "emptyLayers",
            "emptyUnsigned",
            "emptyShadow",
            "emptyShadowLayers"
        };
        int[] units = {5, 5, 5, 6, 6, 7, 8, 9};
        for (int i = 0; i < names.length; i++) glUniform1i(glGetUniformLocation(program, names[i]), units[i]);
    }

    private static int program(boolean compute) {
        int program = glCreateProgram();
        int[] types = compute ? new int[] {GL_COMPUTE_SHADER} : new int[] {GL_VERTEX_SHADER, GL_FRAGMENT_SHADER};
        for (int type : types) {
            int shader = glCreateShader(type);
            try {
                String body = type == GL_VERTEX_SHADER
                        ? "void main(){vec2 p=vec2((gl_VertexID<<1)&2,gl_VertexID&2);gl_Position=vec4(p*2.-1.,0,1);}"
                        : READ
                                + (compute
                                        ? "layout(local_size_x=1)in;layout(std430,binding=0)buffer Result{vec4 pixels[];};void main(){uint i=gl_GlobalInvocationID.x;pixels[i]=readValue(int(i));}"
                                        : "out vec4 colour;void main(){colour=readValue(int(gl_FragCoord.x));}");
                glShaderSource(shader, "#version 430 core\n" + body);
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
