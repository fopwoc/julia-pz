package dev.fopwoc.julia.probe.scene;

import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL12.*;
import static org.lwjgl.opengl.GL13.*;
import static org.lwjgl.opengl.GL15.*;
import static org.lwjgl.opengl.GL20.*;
import static org.lwjgl.opengl.GL30.*;
import static org.lwjgl.opengl.GL43.*;

import dev.fopwoc.julia.probe.adapter.ResourceReadback;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.lwjgl.opengl.GL45;
import org.lwjgl.system.MemoryUtil;

/** The game reproducer plus sampling/operation oracles with several live texture targets on each unit. */
final class TextureBindingChecks {
    private TextureBindingChecks() {}

    static void run(Path output) throws IOException {
        Files.deleteIfExists(output.resolve("texture-bindings.txt"));
        int two = GL45.glCreateTextures(GL_TEXTURE_2D),
                three = GL45.glCreateTextures(GL_TEXTURE_3D),
                array = GL45.glCreateTextures(GL_TEXTURE_2D_ARRAY);
        int target = glGenTextures(), fbo = glGenFramebuffers(), cleared = glGenFramebuffers();
        int twoProgram = 0, threeProgram = 0, arrayProgram = 0, mixedProgram = 0;
        var data = MemoryUtil.memAlloc(4 * 4 * 2 * 4);
        try {
            glActiveTexture(GL_TEXTURE0);
            glBindTexture(GL_TEXTURE_2D, 0);
            glBindTexture(GL_TEXTURE_3D, 0);
            glBindTexture(GL_TEXTURE_2D_ARRAY, 0);
            GL45.glTextureStorage2D(two, 1, GL_RGBA8, 4, 4);
            GL45.glTextureStorage3D(three, 1, GL_RGBA8, 4, 4, 2);
            GL45.glTextureStorage3D(array, 1, GL_RGBA8, 4, 4, 2);
            fill(data, 51, 102, 153);
            GL45.glTextureSubImage2D(two, 0, 0, 0, 4, 4, GL_RGBA, GL_UNSIGNED_BYTE, data);
            fill(data, 204, 51, 102);
            GL45.glTextureSubImage3D(three, 0, 0, 0, 0, 4, 4, 2, GL_RGBA, GL_UNSIGNED_BYTE, data);
            fill(data, 102, 153, 204);
            GL45.glTextureSubImage3D(array, 0, 0, 0, 0, 4, 4, 2, GL_RGBA, GL_UNSIGNED_BYTE, data);
            glBindTexture(GL_TEXTURE_2D, two);
            int savedTwo = glGetInteger(GL_TEXTURE_BINDING_2D), savedThree = glGetInteger(GL_TEXTURE_BINDING_3D);
            if (savedTwo != two || savedThree != 0)
                throw new IllegalStateException("A 2D binding leaked into the saved 3D binding");
            glBindTexture(GL_TEXTURE_3D, three);
            glBindTexture(GL_TEXTURE_3D, savedThree);
            if (glGetInteger(GL_TEXTURE_BINDING_2D) != savedTwo)
                throw new IllegalStateException("Restoring 3D zero disturbed the 2D binding");
            glBindTexture(GL_TEXTURE_3D, three);
            glBindTexture(GL_TEXTURE_2D_ARRAY, array);
            bindings(two, three, array);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
            glTexParameteri(GL_TEXTURE_3D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
            glTexParameteri(GL_TEXTURE_2D_ARRAY, GL_TEXTURE_MIN_FILTER, GL_NEAREST_MIPMAP_NEAREST);
            if (glGetTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER) != GL_NEAREST
                    || glGetTexParameteri(GL_TEXTURE_3D, GL_TEXTURE_MIN_FILTER) != GL_LINEAR
                    || glGetTexParameteri(GL_TEXTURE_2D_ARRAY, GL_TEXTURE_MIN_FILTER) != GL_NEAREST_MIPMAP_NEAREST)
                throw new IllegalStateException("Targeted sampler parameters crossed bindings");
            if (glGetTexLevelParameteri(GL_TEXTURE_3D, 0, GL_TEXTURE_DEPTH) != 2)
                throw new IllegalStateException("3D level query selected the wrong texture");
            // Updating a target must select it even when another target was bound most recently.
            fill(data, 153, 102, 51);
            glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, 4, 4, GL_RGBA, GL_UNSIGNED_BYTE, data);
            bindings(two, three, array);
            glBindTexture(GL_TEXTURE_2D, target);
            glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, 4, 4, 0, GL_RGBA, GL_UNSIGNED_BYTE, 0L);
            glBindTexture(GL_TEXTURE_2D, two);
            glBindFramebuffer(GL_FRAMEBUFFER, fbo);
            glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, target, 0);
            glViewport(0, 0, 4, 4);
            glDisable(GL_BLEND);
            glDisable(GL_DEPTH_TEST);
            glDisable(GL_SCISSOR_TEST);
            glDisable(GL_CULL_FACE);
            twoProgram = program("uniform sampler2D inputData;", "texelFetch(inputData,ivec2(gl_FragCoord.xy),0)");
            threeProgram =
                    program("uniform sampler3D inputData;", "texelFetch(inputData,ivec3(ivec2(gl_FragCoord.xy),1),0)");
            arrayProgram = program(
                    "uniform sampler2DArray inputData;", "texelFetch(inputData,ivec3(ivec2(gl_FragCoord.xy),1),0)");
            mixedProgram = program(
                    "uniform sampler2D inputData;uniform sampler3D other;",
                    "vec4(texelFetch(inputData,ivec2(gl_FragCoord.xy),0).r,texelFetch(other,ivec3(ivec2(gl_FragCoord.xy),1),0).g,0.6,1)");
            glBindTexture(GL_TEXTURE_2D_ARRAY, array);
            draw(twoProgram, target, 153, 102, 51);
            draw(threeProgram, target, 204, 51, 102);
            draw(arrayProgram, target, 102, 153, 204);
            // Per-unit state remains independent, including when the wrong target occupies the old mirror slot.
            glActiveTexture(GL_TEXTURE1);
            glBindTexture(GL_TEXTURE_3D, three);
            glBindTexture(GL_TEXTURE_2D, two);
            bindings(two, three, 0);
            glActiveTexture(GL_TEXTURE0);
            bindings(two, three, array);
            glUseProgram(mixedProgram);
            glUniform1i(glGetUniformLocation(mixedProgram, "other"), 1);
            draw(mixedProgram, target, 153, 51, 153);
            // A pending clear of the hidden 2D texture must be materialized before that sampler reads it.
            glBindFramebuffer(GL_FRAMEBUFFER, cleared);
            glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, two, 0);
            glClearColor(0.2f, 0.4f, 0.6f, 1);
            glClear(GL_COLOR_BUFFER_BIT);
            glBindFramebuffer(GL_FRAMEBUFFER, fbo);
            glBindTexture(GL_TEXTURE_3D, three);
            draw(twoProgram, target, 51, 102, 153);
            compute(three);
            // Deleting a hidden target unbinds that target on every unit without touching the 3D binding.
            glBindTexture(GL_TEXTURE_3D, three);
            glDeleteTextures(two);
            two = 0;
            bindings(0, three, array);
            glActiveTexture(GL_TEXTURE1);
            bindings(0, three, 0);
            glActiveTexture(GL_TEXTURE0);
            draw(threeProgram, target, 204, 51, 102);
            glBindTexture(GL_TEXTURE_3D, 0);
            bindings(0, 0, array);
            draw(arrayProgram, target, 102, 153, 204);
            Files.writeString(
                    output.resolve("texture-bindings.txt"),
                    "PASS\nverified_pixels=112\nverified_compute_words=4\nchecks=save/restore,independent targets/units,scalar/array queries,parameters,uploads,shader switching,mixed samplers,pending clears,compute,deletion,zero binding\n");
        } finally {
            glUseProgram(0);
            glBindFramebuffer(GL_FRAMEBUFFER, 0);
            glActiveTexture(GL_TEXTURE1);
            glBindTexture(GL_TEXTURE_2D, 0);
            glBindTexture(GL_TEXTURE_3D, 0);
            glActiveTexture(GL_TEXTURE0);
            glBindTexture(GL_TEXTURE_2D, 0);
            glBindTexture(GL_TEXTURE_3D, 0);
            glBindTexture(GL_TEXTURE_2D_ARRAY, 0);
            for (int name : new int[] {two, three, array, target}) glDeleteTextures(name);
            for (int name : new int[] {twoProgram, threeProgram, arrayProgram, mixedProgram})
                if (name != 0) glDeleteProgram(name);
            glDeleteFramebuffers(fbo);
            glDeleteFramebuffers(cleared);
            MemoryUtil.memFree(data);
        }
    }

    private static void bindings(int two, int three, int array) {
        int[] query = new int[1];
        glGetIntegerv(GL_TEXTURE_BINDING_2D, query);
        if (query[0] != two
                || glGetInteger(GL_TEXTURE_BINDING_3D) != three
                || glGetInteger(GL_TEXTURE_BINDING_2D_ARRAY) != array)
            throw new IllegalStateException("Texture target/unit bindings crossed: " + query[0] + ","
                    + glGetInteger(GL_TEXTURE_BINDING_3D) + "," + glGetInteger(GL_TEXTURE_BINDING_2D_ARRAY));
    }

    private static void fill(java.nio.ByteBuffer data, int r, int g, int b) {
        for (int i = 0; i < data.capacity(); i += 4) {
            data.put(i, (byte) r);
            data.put(i + 1, (byte) g);
            data.put(i + 2, (byte) b);
            data.put(i + 3, (byte) 255);
        }
    }

    private static int program(String uniforms, String expression) {
        int vertex = glCreateShader(GL_VERTEX_SHADER),
                fragment = glCreateShader(GL_FRAGMENT_SHADER),
                program = glCreateProgram();
        glShaderSource(
                vertex,
                "#version 330 core\nvoid main(){vec2 p=vec2((gl_VertexID<<1)&2,gl_VertexID&2);gl_Position=vec4(p*2.0-1.0,0,1);}");
        glShaderSource(
                fragment, "#version 330 core\n" + uniforms + "out vec4 color;void main(){color=" + expression + ";}");
        for (int shader : new int[] {vertex, fragment}) {
            glCompileShader(shader);
            if (glGetShaderi(shader, GL_COMPILE_STATUS) == GL_FALSE)
                throw new IllegalStateException(glGetShaderInfoLog(shader));
            glAttachShader(program, shader);
            glDeleteShader(shader);
        }
        glLinkProgram(program);
        if (glGetProgrami(program, GL_LINK_STATUS) == GL_FALSE)
            throw new IllegalStateException(glGetProgramInfoLog(program));
        glUseProgram(program);
        glUniform1i(glGetUniformLocation(program, "inputData"), 0);
        return program;
    }

    private static void draw(int program, int target, int r, int g, int b) {
        glUseProgram(program);
        glDrawArrays(GL_TRIANGLES, 0, 3);
        byte[] pixels = ResourceReadback.layer(target, 0);
        int[] expected = {r, g, b, 255};
        for (int i = 0; i < 16; i++)
            for (int c = 0; c < 4; c++)
                if (Math.abs(Byte.toUnsignedInt(pixels[i * 4 + c]) - expected[c]) > 1)
                    throw new IllegalStateException("Target-aware sample pixel=" + i + " channel=" + c + " expected="
                            + expected[c] + " actual=" + Byte.toUnsignedInt(pixels[i * 4 + c]));
    }

    private static void compute(int three) {
        int shader = glCreateShader(GL_COMPUTE_SHADER), program = glCreateProgram(), buffer = glGenBuffers();
        var bytes = MemoryUtil.memAlloc(16);
        try {
            glShaderSource(
                    shader,
                    "#version 430 core\nlayout(local_size_x=1)in;uniform sampler3D inputData;layout(std430,binding=1)buffer Result{vec4 value;};void main(){value=texelFetch(inputData,ivec3(0,0,1),0);}");
            glCompileShader(shader);
            if (glGetShaderi(shader, GL_COMPILE_STATUS) == GL_FALSE)
                throw new IllegalStateException(glGetShaderInfoLog(shader));
            glAttachShader(program, shader);
            glLinkProgram(program);
            if (glGetProgrami(program, GL_LINK_STATUS) == GL_FALSE)
                throw new IllegalStateException(glGetProgramInfoLog(program));
            glUseProgram(program);
            glUniform1i(glGetUniformLocation(program, "inputData"), 0);
            if (glGetInteger(GL_TEXTURE_BINDING_3D) != three)
                throw new IllegalStateException("Compute test lost the volume binding");
            // Select the 2D execution mirror without changing the logical 3D binding.
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
            glBindBuffer(GL_SHADER_STORAGE_BUFFER, buffer);
            glBufferData(GL_SHADER_STORAGE_BUFFER, 16L, GL_DYNAMIC_READ);
            glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 1, buffer);
            glDispatchCompute(1, 1, 1);
            glMemoryBarrier(GL_SHADER_STORAGE_BARRIER_BIT | GL_BUFFER_UPDATE_BARRIER_BIT);
            glGetBufferSubData(GL_SHADER_STORAGE_BUFFER, 0L, bytes);
            float[] expected = {0.8f, 0.2f, 0.4f, 1};
            for (int i = 0; i < 4; i++)
                if (Math.abs(bytes.getFloat(i * 4) - expected[i]) > 0.001f)
                    throw new IllegalStateException("Compute selected wrong texture target");
        } finally {
            glUseProgram(0);
            glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 1, 0);
            glBindBuffer(GL_SHADER_STORAGE_BUFFER, 0);
            glDeleteBuffers(buffer);
            glDeleteProgram(program);
            glDeleteShader(shader);
            MemoryUtil.memFree(bytes);
        }
    }
}
