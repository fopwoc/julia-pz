package dev.julia.probe.adapter;

import com.gtnewhorizons.angelica.glsm.backend.BackendManager;
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
final class AdapterDraw {
    private static final int SIZE = 32;

    private AdapterDraw() { }

    static void run(Path output) throws IOException {
        int target = 0;
        int source = 0;
        int framebuffer = 0;
        int program = 0;
        var backend = BackendManager.RENDER_BACKEND;
        boolean frameOpen = false;
        try {
            backend.onFrameBegin();
            frameOpen = true;
            target = texture(SIZE, null);
            framebuffer = glGenFramebuffers();
            glBindFramebuffer(GL_FRAMEBUFFER, framebuffer);
            glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, target, 0);
            int status = glCheckFramebufferStatus(GL_FRAMEBUFFER);
            if (status != GL_FRAMEBUFFER_COMPLETE) throw new IllegalStateException("Incomplete FBO: " + status);
            glViewport(0, 0, SIZE, SIZE);
            glDisable(GL_DEPTH_TEST);
            glDisable(GL_BLEND);
            glDisable(GL_CULL_FACE);
            glDisable(GL_SCISSOR_TEST);
            glClearColor(1, 0, 1, 1);
            glClear(GL_COLOR_BUFFER_BIT);
            ByteBuffer upload = MemoryUtil.memAlloc(TexturePattern.EDGE * TexturePattern.EDGE * 4);
            try {
                for (int y = 0; y < TexturePattern.EDGE; y++) {
                    for (int x = 0; x < TexturePattern.EDGE; x++) {
                        int color = TexturePattern.sourceArgb(x, y);
                        upload.put((byte) (color >>> 16)).put((byte) (color >>> 8)).put((byte) color).put((byte) 255);
                    }
                }
                upload.flip();
                source = texture(TexturePattern.EDGE, upload);
            } finally {
                MemoryUtil.memFree(upload);
            }
            program = glCreateProgram();
            attach(program, GL_VERTEX_SHADER, "/shaders/adapter.vert");
            attach(program, GL_FRAGMENT_SHADER, "/shaders/texture.frag");
            glLinkProgram(program);
            if (glGetProgrami(program, GL_LINK_STATUS) == 0) throw new IllegalStateException(glGetProgramInfoLog(program));
            glUseProgram(program);
            int sampler = glGetUniformLocation(program, "image");
            if (sampler < 0) throw new IllegalStateException("Sampler uniform is missing");
            glUniform1i(sampler, 0);
            glDrawArrays(GL_TRIANGLES, 0, 3);
            backend.onFrameEnd();
            frameOpen = false;
            ByteBuffer pixels = MemoryUtil.memAlloc(SIZE * SIZE * 4);
            try {
                glReadPixels(0, 0, SIZE, SIZE, GL_RGBA, GL_UNSIGNED_BYTE, pixels);
                var image = new BufferedImage(SIZE, SIZE, BufferedImage.TYPE_INT_ARGB);
                for (int y = 0; y < SIZE; y++) {
                    for (int x = 0; x < SIZE; x++) {
                        int offset = (y * SIZE + x) * 4;
                        int actual = (pixels.get(offset + 3) & 255) << 24 | (pixels.get(offset) & 255) << 16
                                | (pixels.get(offset + 1) & 255) << 8 | pixels.get(offset + 2) & 255;
                        int expected = TexturePattern.expectedArgb(x, y, SIZE);
                        if (actual != expected) throw new IllegalStateException("GLSM pixel " + x + "," + y
                                + " expected " + Integer.toHexString(expected) + ", got " + Integer.toHexString(actual));
                        // glReadPixels uses a bottom-left origin; PNG uses a top-left origin.
                        image.setRGB(x, SIZE - 1 - y, actual);
                    }
                }
                if (!ImageIO.write(image, "png", output.resolve("adapter-texture.png").toFile())) {
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

    private static int texture(int size, ByteBuffer pixels) {
        int id = glGenTextures();
        glBindTexture(GL_TEXTURE_2D, id);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, size, size, 0, GL_RGBA, GL_UNSIGNED_BYTE, pixels);
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
