package dev.fopwoc.julia.probe.scene;

import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL13.*;
import static org.lwjgl.opengl.GL20.*;
import static org.lwjgl.opengl.GL30.*;
import static org.lwjgl.opengl.GL43.*;

import dev.fopwoc.julia.probe.adapter.ResourceReadback;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.lwjgl.opengl.ARBTextureView;
import org.lwjgl.opengl.GL45;
import org.lwjgl.system.MemoryUtil;

/** Native views must share layer/mip contents, render writes and storage lifetime, not copies. */
final class LayerViewChecks {
    private static int verified;

    private LayerViewChecks() {}

    static void run(Path output) throws IOException {
        verified = 0;
        Files.deleteIfExists(output.resolve("layer-views.txt"));
        int root = GL45.glCreateTextures(GL_TEXTURE_2D_ARRAY);
        int view = glGenTextures(), subset = glGenTextures(), nested = glGenTextures();
        int depth = GL45.glCreateTextures(GL_TEXTURE_2D_ARRAY);
        int[] depthViews = {glGenTextures(), glGenTextures(), glGenTextures()};
        int target = GL45.glCreateTextures(GL_TEXTURE_2D);
        int drawFbo = GL45.glCreateFramebuffers(), editFbo = GL45.glCreateFramebuffers();
        int arrayProgram = program(true), flatProgram = program(false);
        var pixels = MemoryUtil.memAlloc(8 * 8 * 3 * 4);
        try {
            glDisable(GL_DEPTH_TEST);
            glDisable(GL_STENCIL_TEST);
            glDisable(GL_BLEND);
            glDisable(GL_SCISSOR_TEST);
            glDisable(GL_CULL_FACE);
            glColorMask(true, true, true, true);
            glDepthMask(true);
            glActiveTexture(GL_TEXTURE0);
            GL45.glTextureStorage3D(root, 3, GL_RGBA8, 8, 8, 3);
            for (int mip = 0; mip < 3; mip++) {
                int size = 8 >> mip;
                pixels.clear();
                for (int layer = 0; layer < 3; layer++)
                    for (int i = 0; i < size * size; i++)
                        pixels.put((byte) (layer == 0 ? 255 : 0))
                                .put((byte) (layer == 1 ? 255 : 0))
                                .put((byte) (layer == 2 ? 255 : 0))
                                .put((byte) 255);
                pixels.flip();
                GL45.glTextureSubImage3D(root, mip, 0, 0, 0, size, size, 3, GL_RGBA, GL_UNSIGNED_BYTE, pixels);
            }
            glTextureView(view, GL_TEXTURE_2D, root, GL_RGBA8, 1, 1, 1, 1);
            glTextureView(subset, GL_TEXTURE_2D_ARRAY, root, GL_RGBA8, 1, 99, 1, 99);
            ARBTextureView.glTextureView(nested, GL_TEXTURE_2D, subset, GL_RGBA8, 1, 99, 1, 1);
            glBindTexture(GL_TEXTURE_2D, nested);
            if (glGetTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_VIEW_MIN_LEVEL) != 2
                    || glGetTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_VIEW_MIN_LAYER) != 2
                    || glGetTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_VIEW_NUM_LEVELS) != 1
                    || glGetTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_IMMUTABLE_LEVELS) != 3)
                throw new IllegalStateException("Nested native view metadata differs from shared storage");
            glBindTexture(GL_TEXTURE_2D_ARRAY, root);
            if (glGetTexParameteri(GL_TEXTURE_2D_ARRAY, GL_TEXTURE_VIEW_NUM_LAYERS) != 3)
                throw new IllegalStateException("Array storage layer query is not its actual depth");
            GL45.glTextureStorage2D(target, 1, GL_RGBA8, 4, 4);
            GL45.glNamedFramebufferTexture(drawFbo, GL_COLOR_ATTACHMENT0, target, 0);
            GL45.glNamedFramebufferTexture(editFbo, GL_COLOR_ATTACHMENT0, view, 0);
            glBindFramebuffer(GL_FRAMEBUFFER, editFbo);
            glViewport(0, 0, 4, 4);
            glClearColor(1, 1, 0, 1);
            glClear(GL_COLOR_BUFFER_BIT);
            glBindFramebuffer(GL_FRAMEBUFFER, drawFbo);
            glUseProgram(arrayProgram);
            glBindTexture(GL_TEXTURE_2D_ARRAY, root);
            for (int layer = 0; layer < 3; layer++) {
                glUniform1i(glGetUniformLocation(arrayProgram, "layer"), layer);
                glUniform1i(glGetUniformLocation(arrayProgram, "mip"), 1);
                glDrawArrays(GL_TRIANGLES, 0, 3);
                verify(target, layer == 0 || layer == 1 ? 255 : 0, layer == 1 ? 255 : 0, layer == 2 ? 255 : 0);
            }
            // Named uploads through the native 2D view also address its selected parent mip/layer.
            pixels.clear();
            for (int i = 0; i < 16; i++) pixels.putInt(-1);
            pixels.flip();
            GL45.glTextureSubImage2D(view, 0, 0, 0, 4, 4, GL_RGBA, GL_UNSIGNED_BYTE, pixels);
            glUniform1i(glGetUniformLocation(arrayProgram, "layer"), 1);
            glDrawArrays(GL_TRIANGLES, 0, 3);
            verify(target, 255, 255, 255);
            // A view-of-view keeps the underlying allocation after both source names are deleted.
            glDeleteTextures(root);
            glDeleteTextures(subset);
            glUseProgram(flatProgram);
            glBindTexture(GL_TEXTURE_2D, nested);
            glDrawArrays(GL_TRIANGLES, 0, 3);
            verify(target, 0, 0, 255);
            glBindTexture(GL_TEXTURE_2D, view);
            glDrawArrays(GL_TRIANGLES, 0, 3);
            verify(target, 255, 255, 255);
            // Reproduce shadow setup: D24 array, one 2D view/FBO per cascade, sampled through the array.
            GL45.glTextureStorage3D(depth, 1, GL_DEPTH_COMPONENT24, 4, 4, 3);
            GL45.glTextureParameteri(depth, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
            GL45.glTextureParameteri(depth, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
            GL45.glNamedFramebufferTexture(editFbo, GL_COLOR_ATTACHMENT0, 0, 0);
            GL45.glNamedFramebufferDrawBuffer(editFbo, GL_NONE);
            GL45.glNamedFramebufferReadBuffer(editFbo, GL_NONE);
            for (int i = 0; i < 3; i++) {
                glTextureView(depthViews[i], GL_TEXTURE_2D, depth, GL_DEPTH_COMPONENT24, 0, 1, i, 1);
                GL45.glNamedFramebufferTexture(editFbo, GL_DEPTH_ATTACHMENT, depthViews[i], 0);
                if (GL45.glCheckNamedFramebufferStatus(editFbo, GL_FRAMEBUFFER) != GL_FRAMEBUFFER_COMPLETE)
                    throw new IllegalStateException("Shadow view FBO incomplete");
                glBindFramebuffer(GL_FRAMEBUFFER, editFbo);
                glClearDepth((i + 1) * 0.25);
                glClear(GL_DEPTH_BUFFER_BIT);
            }
            glBindFramebuffer(GL_FRAMEBUFFER, drawFbo);
            glUseProgram(arrayProgram);
            glBindTexture(GL_TEXTURE_2D_ARRAY, depth);
            glUniform1i(glGetUniformLocation(arrayProgram, "mip"), 0);
            glUniform1i(glGetUniformLocation(arrayProgram, "gray"), 1);
            for (int i = 0; i < 3; i++) {
                glUniform1i(glGetUniformLocation(arrayProgram, "layer"), i);
                glDrawArrays(GL_TRIANGLES, 0, 3);
                int expected = Math.round((i + 1) * 0.25f * 255);
                verify(target, expected, expected, expected);
            }
            // Depth writes through a 2D view must update only that cascade in the parent array.
            glBindFramebuffer(GL_FRAMEBUFFER, editFbo);
            glUseProgram(flatProgram);
            glBindTexture(GL_TEXTURE_2D, view);
            glEnable(GL_DEPTH_TEST);
            glDepthFunc(GL_LESS);
            glDrawArrays(GL_TRIANGLES, 0, 3);
            glDisable(GL_DEPTH_TEST);
            glBindFramebuffer(GL_FRAMEBUFFER, drawFbo);
            glUseProgram(arrayProgram);
            for (int i = 0; i < 3; i++) {
                glUniform1i(glGetUniformLocation(arrayProgram, "layer"), i);
                glDrawArrays(GL_TRIANGLES, 0, 3);
                int expected = i == 0 ? 64 : 128;
                verify(target, expected, expected, expected);
            }
            glDeleteTextures(depth);
            glUseProgram(flatProgram);
            glBindTexture(GL_TEXTURE_2D, depthViews[1]);
            glDrawArrays(GL_TRIANGLES, 0, 3);
            verify(target, 128, 128, 128);
            Files.writeString(
                    output.resolve("layer-views.txt"),
                    "PASS\nverified_pixels=" + verified
                            + "\nchecks=GL43/ARB entry points,2D layer views,array subsets,nested mip/layer ranges,uploads,shared FBO clears,parent deletion,D24 shadow views,depth-tested writes\n");
        } finally {
            glUseProgram(0);
            glBindFramebuffer(GL_FRAMEBUFFER, 0);
            glClearDepth(1);
            glDeleteFramebuffers(drawFbo);
            glDeleteFramebuffers(editFbo);
            for (int texture : new int[] {root, view, subset, nested, depth, target}) glDeleteTextures(texture);
            glDeleteTextures(depthViews);
            glDeleteProgram(arrayProgram);
            glDeleteProgram(flatProgram);
            MemoryUtil.memFree(pixels);
        }
    }

    private static void verify(int target, int red, int green, int blue) {
        byte[] data = ResourceReadback.layer(target, 0);
        for (int i = 0; i < data.length; i += 4) {
            if (Math.abs((data[i] & 255) - red) > 1
                    || Math.abs((data[i + 1] & 255) - green) > 1
                    || Math.abs((data[i + 2] & 255) - blue) > 1
                    || (data[i + 3] & 255) != 255)
                throw new IllegalStateException("Shared layer-view pixel mismatch: expected " + red + "," + green + ","
                        + blue + " got " + (data[i] & 255) + "," + (data[i + 1] & 255) + "," + (data[i + 2] & 255));
            verified++;
        }
    }

    private static int program(boolean array) {
        int vertex = glCreateShader(GL_VERTEX_SHADER),
                fragment = glCreateShader(GL_FRAGMENT_SHADER),
                program = glCreateProgram();
        glShaderSource(
                vertex,
                "#version 330 core\nvoid main(){vec2 p=vec2((gl_VertexID<<1)&2,gl_VertexID&2);gl_Position=vec4(p*2.-1.,0,1);}");
        glShaderSource(
                fragment,
                array
                        ? "#version 330 core\nuniform sampler2DArray source;uniform int layer;uniform int mip;uniform int gray;out vec4 color;void main(){vec4 c=texelFetch(source,ivec3(0,0,layer),mip);color=gray!=0?vec4(vec3(c.r),1):c;}"
                        : "#version 330 core\nuniform sampler2D source;out vec4 color;void main(){color=texelFetch(source,ivec2(0),0);}");
        try {
            for (int shader : new int[] {vertex, fragment}) {
                glCompileShader(shader);
                if (glGetShaderi(shader, GL_COMPILE_STATUS) == 0)
                    throw new IllegalStateException(glGetShaderInfoLog(shader));
                glAttachShader(program, shader);
            }
            glLinkProgram(program);
            if (glGetProgrami(program, GL_LINK_STATUS) == 0)
                throw new IllegalStateException(glGetProgramInfoLog(program));
            return program;
        } finally {
            glDeleteShader(vertex);
            glDeleteShader(fragment);
        }
    }
}
