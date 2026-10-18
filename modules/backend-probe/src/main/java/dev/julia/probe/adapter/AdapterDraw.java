package dev.julia.probe.adapter;

import com.gtnewhorizons.angelica.glsm.backend.BackendManager;
import com.gtnewhorizons.angelica.glsm.profiling.DebugCounters;
import dev.julia.probe.render.TexturePattern;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import javax.imageio.ImageIO;
import org.lwjgl.system.MemoryUtil;
import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL12.GL_CLAMP_TO_EDGE;
import static org.lwjgl.opengl.GL20.*;
import static org.lwjgl.opengl.GL30.*;

/** Ordinary LWJGL OpenGL calls; the isolated loader must redirect every call before this scene can run. */
public final class AdapterDraw {
    private AdapterDraw() { }

    static void run(Path output, int width, int height) throws IOException { run(output, width, height, false); }

    public static void runWindow(Path output, int width, int height) throws IOException {
        run(output, width, height, true);
    }

    private static void run(Path output, int width, int height, boolean window) throws IOException {
        int target = 0;
        int source = 0;
        int framebuffer = 0;
        int program = 0;
        var backend = BackendManager.RENDER_BACKEND;
        boolean frameOpen = false;
        try {
            backend.onFrameBegin();
            frameOpen = true;
            if (!window) {
                target = texture(width, height, null);
                framebuffer = glGenFramebuffers();
                glBindFramebuffer(GL_FRAMEBUFFER, framebuffer);
                glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, target, 0);
                int status = glCheckFramebufferStatus(GL_FRAMEBUFFER);
                if (status != GL_FRAMEBUFFER_COMPLETE) throw new IllegalStateException("Incomplete FBO: " + status);
            } else {
                glBindFramebuffer(GL_FRAMEBUFFER, 0);
            }
            var capabilities = org.lwjgl.opengl.GL.getCapabilities();
            if (!capabilities.OpenGL33 || !capabilities.GL_ARB_framebuffer_object || capabilities.GL_NVX_gpu_memory_info) {
                throw new IllegalStateException("Backend capability snapshot mismatch");
            }
            org.lwjgl.opengl.GL43.glClearDepthf(0.25f);
            if (glGetDouble(GL_DEPTH_CLEAR_VALUE) != 0.25) throw new IllegalStateException("Depth conversion mismatch");
            org.lwjgl.opengl.GL43.glClearDepthf(1.0f);
            verifyArrayAllocation();
            glViewport(0, 0, width, height);
            glDisable(GL_DEPTH_TEST);
            glDisable(GL_BLEND);
            glDisable(GL_CULL_FACE);
            glDisable(GL_SCISSOR_TEST);
            glClearColor(1, 0, 1, 1);
            ByteBuffer upload = MemoryUtil.memAlloc(TexturePattern.EDGE * TexturePattern.EDGE * 4);
            try {
                for (int y = 0; y < TexturePattern.EDGE; y++) {
                    for (int x = 0; x < TexturePattern.EDGE; x++) {
                        int color = TexturePattern.sourceArgb(x, y);
                        upload.put((byte) (color >>> 16)).put((byte) (color >>> 8)).put((byte) color).put((byte) 255);
                    }
                }
                upload.flip();
                source = texture(TexturePattern.EDGE, TexturePattern.EDGE, upload);
            } finally {
                MemoryUtil.memFree(upload);
            }
            int uploaded = source;
            source = texture(TexturePattern.EDGE, TexturePattern.EDGE, null);
            try {
                org.lwjgl.opengl.GL44.glCopyImageSubData(uploaded, GL_TEXTURE_2D, 0, 0, 0, 0,
                        source, GL_TEXTURE_2D, 0, 0, 0, 0, TexturePattern.EDGE, TexturePattern.EDGE, 1);
            } finally { glDeleteTextures(uploaded); }
            program = glCreateProgram();
            attach(program, GL_VERTEX_SHADER, "/shaders/adapter.vert");
            attach(program, GL_FRAGMENT_SHADER, "/shaders/adapter.frag");
            glLinkProgram(program);
            if (glGetProgrami(program, GL_LINK_STATUS) == 0) throw new IllegalStateException(glGetProgramInfoLog(program));
            glUseProgram(program);
            int sampler = glGetUniformLocation(program, "image");
            if (sampler < 0) throw new IllegalStateException("Sampler uniform is missing");
            glUniform1i(sampler, 0);
            glUniform1fv(glGetUniformLocation(program, "weights"), new float[] {0.25f, 0.75f});
            glUniformMatrix4fv(glGetUniformLocation(program, "transform"), true, new float[] {
                    1, 0, 0, 0.25f, 0, 1, 0, -0.5f, 0, 0, 1, 0, 0, 0, 0, 1, 99, 98, 97});
            try (var stack = org.lwjgl.system.MemoryStack.stackPush()) {
                var shift = stack.floats(99.0f, 0.25f, 0.5f);
                shift.position(1);
                org.lwjgl.opengl.GL43.glUniform2fv(glGetUniformLocation(program, "shift"), shift);
            }
            org.lwjgl.opengl.GL43.glUniform1iv(glGetUniformLocation(program, "channels"), new int[] {3, 5});
            org.lwjgl.opengl.GL43.glUniform1iv(glGetUniformLocation(program, "enabled"), new int[] {7});
            org.lwjgl.opengl.GL43.glUniform1iv(sampler, new int[] {0});
            verifyUniforms(program);
            verifyUniformValues(program);
            long presents = DebugCounters.PRESENTS.sum();
            for (int frame = 0; frame < 3; frame++) {
                if (frame != 0) {
                    backend.onFrameBegin();
                    frameOpen = true;
                }
                glClear(GL_COLOR_BUFFER_BIT);
                glDrawArrays(GL_TRIANGLES, 0, 3);
                backend.onFrameEnd();
                frameOpen = false;
                if (!window && !org.lwjgl.sdl.SDLGPU.SDL_WaitForGPUIdle(
                        com.gtnewhorizons.angelica.sdlgpu.SDLGPUGate.device().getDevice())) {
                    throw new IllegalStateException("Cannot drain the offscreen frame");
                }
            }
            if (window && DebugCounters.PRESENTS.sum() - presents != 3) {
                throw new IllegalStateException("Expected three successful swapchain submissions; got "
                        + (DebugCounters.PRESENTS.sum() - presents));
            }
            ByteBuffer pixels = MemoryUtil.memAlloc(width * height * 4);
            try {
                glReadPixels(0, 0, width, height, GL_RGBA, GL_UNSIGNED_BYTE, pixels);
                var image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
                for (int y = 0; y < height; y++) {
                    for (int x = 0; x < width; x++) {
                        int offset = (y * width + x) * 4;
                        int actual = (pixels.get(offset + 3) & 255) << 24 | (pixels.get(offset) & 255) << 16
                                | (pixels.get(offset + 1) & 255) << 8 | pixels.get(offset + 2) & 255;
                        int expected = TexturePattern.expectedArgb(x, y, width, height);
                        if (!TexturePattern.matchesArgb(actual, x, y, width, height)) throw new IllegalStateException("GLSM pixel " + x + "," + y
                                + " in " + width + "x" + height + " expected " + Integer.toHexString(expected) + ", got " + Integer.toHexString(actual));
                        // glReadPixels uses a bottom-left origin; PNG uses a top-left origin.
                        image.setRGB(x, height - 1 - y, actual);
                    }
                }
                if (!ImageIO.write(image, "png", output.resolve(window ? "window-texture.png" : "adapter-texture.png").toFile())) {
                    throw new IOException("No PNG writer available");
                }
            } finally {
                MemoryUtil.memFree(pixels);
            }
            int error = glGetError();
            if (error != GL_NO_ERROR) throw new IllegalStateException("GLSM error: " + error);
        } finally {
            if (frameOpen) backend.onFrameEnd();
            glUseProgram(0);
            glBindFramebuffer(GL_FRAMEBUFFER, 0);
            glBindTexture(GL_TEXTURE_2D, 0);
            if (program != 0) glDeleteProgram(program);
            if (framebuffer != 0) glDeleteFramebuffers(framebuffer);
            if (source != 0) glDeleteTextures(source);
            if (target != 0) glDeleteTextures(target);
        }
    }

    private static void verifyUniformValues(int program) {
        int sampler = glGetUniformLocation(program, "image");
        int channels = glGetUniformLocation(program, "channels");
        glUseProgram(0);
        try (var stack = org.lwjgl.system.MemoryStack.stackPush()) {
            if (org.lwjgl.opengl.GL43.glGetUniformi(program, sampler) != 0
                    || org.lwjgl.opengl.GL43.glGetUniformi(program, glGetUniformLocation(program, "enabled")) != 1
                    || org.lwjgl.opengl.GL43.glGetUniformf(program, channels) != 3.0f) {
                throw new IllegalStateException("Sampler/integer/boolean uniform state mismatch");
            }
            var vector = stack.floats(99, 88, 77, 66);
            vector.position(1);
            org.lwjgl.opengl.GL43.glGetUniformfv(program, glGetUniformLocation(program, "shift"), vector);
            if (vector.position() != 1 || vector.get(0) != 99 || vector.get(1) != 0.25f || vector.get(2) != 0.5f || vector.get(3) != 66) {
                throw new IllegalStateException("Uniform vector query output mismatch");
            }
            float[] weights = {-99, -88};
            org.lwjgl.opengl.GL43.glGetUniformfv(program, glGetUniformLocation(program, "weights"), weights);
            if (weights[0] != 0.25f || weights[1] != -88) throw new IllegalStateException("Array query must return one element");
            int[] integers = {-99, -88};
            org.lwjgl.opengl.GL43.glGetUniformiv(program, channels, integers);
            if (integers[0] != 3 || integers[1] != -88) throw new IllegalStateException("Integer array query mismatch");
            float[] matrix = new float[16];
            org.lwjgl.opengl.GL43.glGetUniformfv(program, glGetUniformLocation(program, "transform"), matrix);
            if (matrix[0] != 1 || matrix[5] != 1 || matrix[10] != 1 || matrix[15] != 1 || matrix[12] != 0.25f || matrix[13] != -0.5f) {
                throw new IllegalStateException("Matrix query is not column-major");
            }
        } finally { glUseProgram(program); }
    }

    private static void verifyArrayAllocation() {
        int texture = glGenTextures();
        int buffer = org.lwjgl.opengl.GL15.glGenBuffers();
        try {
            glBindTexture(org.lwjgl.opengl.GL30.GL_TEXTURE_2D_ARRAY, texture);
            org.lwjgl.opengl.GL12.glTexImage3D(org.lwjgl.opengl.GL30.GL_TEXTURE_2D_ARRAY, 0, GL_RGBA8, 8, 4, 2, 0, GL_RGBA, GL_UNSIGNED_BYTE, 0L);
            if (glGetTexLevelParameteri(org.lwjgl.opengl.GL30.GL_TEXTURE_2D_ARRAY, 0, GL_TEXTURE_WIDTH) != 8
                    || glGetTexLevelParameteri(org.lwjgl.opengl.GL30.GL_TEXTURE_2D_ARRAY, 0, GL_TEXTURE_HEIGHT) != 4) {
                throw new IllegalStateException("Null-pointer array allocation dimensions mismatch");
            }
            org.lwjgl.opengl.GL15.glBindBuffer(org.lwjgl.opengl.GL21.GL_PIXEL_UNPACK_BUFFER, buffer);
            boolean rejected = false;
            try {
                org.lwjgl.opengl.GL12.glTexImage3D(org.lwjgl.opengl.GL30.GL_TEXTURE_2D_ARRAY, 0, GL_RGBA8, 8, 4, 2, 0, GL_RGBA, GL_UNSIGNED_BYTE, 0L);
            } catch (UnsupportedOperationException expected) { rejected = true; }
            if (!rejected) throw new IllegalStateException("PBO offset zero was mistaken for null client storage");
        } finally {
            org.lwjgl.opengl.GL15.glBindBuffer(org.lwjgl.opengl.GL21.GL_PIXEL_UNPACK_BUFFER, 0);
            glBindTexture(org.lwjgl.opengl.GL30.GL_TEXTURE_2D_ARRAY, 0);
            org.lwjgl.opengl.GL15.glDeleteBuffers(buffer);
            glDeleteTextures(texture);
        }
    }

    private static void verifyUniforms(int program) {
        try (var stack = org.lwjgl.system.MemoryStack.stackPush()) {
            var size = stack.callocInt(3);
            var type = stack.callocInt(3);
            size.put(0, -77).position(1);
            type.put(0, -88).position(1);
            boolean weights = false;
            boolean transform = false;
            int count = glGetProgrami(program, GL_ACTIVE_UNIFORMS);
            for (int index = 0; index < count; index++) {
                String name = glGetActiveUniform(program, index, 128, size, type);
                if (name.equals("weights") || name.equals("weights[0]")) {
                    weights = size.get(1) == 2 && type.get(1) == GL_FLOAT;
                }
                if (name.equals("transform")) transform = size.get(1) == 1 && type.get(1) == GL_FLOAT_MAT4;
                if (!glGetActiveUniform(program, index, 0, size, type).isEmpty()
                        || !glGetActiveUniform(program, index, 1, size, type).isEmpty()) {
                    throw new IllegalStateException("Zero-capacity uniform names must be empty");
                }
                String truncated = glGetActiveUniform(program, index, 4, size, type);
                if (!truncated.equals(name.substring(0, Math.min(3, name.length())))) {
                    throw new IllegalStateException("Uniform name truncation mismatch: " + truncated);
                }
            }
            if (!weights || !transform || size.position() != 1 || type.position() != 1
                    || size.get(0) != -77 || type.get(0) != -88) {
                throw new IllegalStateException("Uniform reflection or output buffer positions are incorrect");
            }
        }
    }

    private static int texture(int width, int height, ByteBuffer pixels) {
        int id = glGenTextures();
        glBindTexture(GL_TEXTURE_2D, id);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, width, height, 0, GL_RGBA, GL_UNSIGNED_BYTE, pixels);
        return id;
    }

    private static void attach(int program, int stage, String resource) throws IOException {
        int shader = glCreateShader(stage);
        try {
            try (var stream = AdapterDraw.class.getResourceAsStream(resource)) {
                if (stream == null) throw new IOException("Missing shader " + resource);
                glShaderSource(shader, new String(stream.readAllBytes(), StandardCharsets.UTF_8));
            }
            glCompileShader(shader);
            if (glGetShaderi(shader, GL_COMPILE_STATUS) == 0) throw new IllegalStateException(glGetShaderInfoLog(shader));
            glAttachShader(program, shader);
        } finally {
            glDeleteShader(shader);
        }
    }
}
