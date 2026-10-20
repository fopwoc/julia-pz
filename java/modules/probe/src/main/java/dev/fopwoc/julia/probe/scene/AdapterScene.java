package dev.fopwoc.julia.probe.scene;

import static org.lwjgl.opengl.GL11.GL_BLEND;
import static org.lwjgl.opengl.GL11.GL_COLOR_BUFFER_BIT;
import static org.lwjgl.opengl.GL11.GL_CULL_FACE;
import static org.lwjgl.opengl.GL11.GL_DEPTH_TEST;
import static org.lwjgl.opengl.GL11.GL_NEAREST;
import static org.lwjgl.opengl.GL11.GL_NO_ERROR;
import static org.lwjgl.opengl.GL11.GL_RGBA;
import static org.lwjgl.opengl.GL11.GL_RGBA8;
import static org.lwjgl.opengl.GL11.GL_SCISSOR_TEST;
import static org.lwjgl.opengl.GL11.GL_TEXTURE_2D;
import static org.lwjgl.opengl.GL11.GL_TEXTURE_MAG_FILTER;
import static org.lwjgl.opengl.GL11.GL_TEXTURE_MIN_FILTER;
import static org.lwjgl.opengl.GL11.GL_TEXTURE_WRAP_S;
import static org.lwjgl.opengl.GL11.GL_TEXTURE_WRAP_T;
import static org.lwjgl.opengl.GL11.GL_TRIANGLES;
import static org.lwjgl.opengl.GL11.GL_UNSIGNED_BYTE;
import static org.lwjgl.opengl.GL11.glBindTexture;
import static org.lwjgl.opengl.GL11.glClear;
import static org.lwjgl.opengl.GL11.glClearColor;
import static org.lwjgl.opengl.GL11.glDeleteTextures;
import static org.lwjgl.opengl.GL11.glDisable;
import static org.lwjgl.opengl.GL11.glDrawArrays;
import static org.lwjgl.opengl.GL11.glGenTextures;
import static org.lwjgl.opengl.GL11.glGetError;
import static org.lwjgl.opengl.GL11.glReadPixels;
import static org.lwjgl.opengl.GL11.glTexImage2D;
import static org.lwjgl.opengl.GL11.glTexParameteri;
import static org.lwjgl.opengl.GL11.glViewport;
import static org.lwjgl.opengl.GL12.GL_CLAMP_TO_EDGE;
import static org.lwjgl.opengl.GL20.GL_COMPILE_STATUS;
import static org.lwjgl.opengl.GL20.GL_FRAGMENT_SHADER;
import static org.lwjgl.opengl.GL20.GL_LINK_STATUS;
import static org.lwjgl.opengl.GL20.GL_VERTEX_SHADER;
import static org.lwjgl.opengl.GL20.glAttachShader;
import static org.lwjgl.opengl.GL20.glCompileShader;
import static org.lwjgl.opengl.GL20.glCreateProgram;
import static org.lwjgl.opengl.GL20.glCreateShader;
import static org.lwjgl.opengl.GL20.glDeleteProgram;
import static org.lwjgl.opengl.GL20.glDeleteShader;
import static org.lwjgl.opengl.GL20.glGetProgramInfoLog;
import static org.lwjgl.opengl.GL20.glGetProgrami;
import static org.lwjgl.opengl.GL20.glGetShaderInfoLog;
import static org.lwjgl.opengl.GL20.glGetShaderi;
import static org.lwjgl.opengl.GL20.glLinkProgram;
import static org.lwjgl.opengl.GL20.glShaderSource;
import static org.lwjgl.opengl.GL20.glUseProgram;
import static org.lwjgl.opengl.GL30.GL_COLOR_ATTACHMENT0;
import static org.lwjgl.opengl.GL30.GL_FRAMEBUFFER;
import static org.lwjgl.opengl.GL30.GL_FRAMEBUFFER_COMPLETE;
import static org.lwjgl.opengl.GL30.glBindFramebuffer;
import static org.lwjgl.opengl.GL30.glCheckFramebufferStatus;
import static org.lwjgl.opengl.GL30.glDeleteFramebuffers;
import static org.lwjgl.opengl.GL30.glFramebufferTexture2D;
import static org.lwjgl.opengl.GL30.glGenFramebuffers;

import com.gtnewhorizons.angelica.glsm.backend.BackendManager;
import com.gtnewhorizons.angelica.glsm.profiling.DebugCounters;
import com.gtnewhorizons.angelica.sdlgpu.SDLGPUGate;
import dev.fopwoc.julia.probe.device.TexturePattern;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.imageio.ImageIO;
import org.lwjgl.opengl.GL44;
import org.lwjgl.sdl.SDLGPU;
import org.lwjgl.system.MemoryUtil;

/**
 * Draws a sampled, swizzled texture through the redirected GL API for {@link #FRAMES} frames, then verifies every
 * pixel of the result. Offscreen it renders into an FBO; in a window it presents to the default framebuffer.
 */
public final class AdapterScene {
    public static final int FRAMES = 3;

    private AdapterScene() {}

    public static void offscreen(Path output, int width, int height) throws IOException {
        run(output, width, height, false);
    }

    public static void window(Path output, int width, int height) throws IOException {
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
            Files.deleteIfExists(output.resolve("resource-checks.txt"));
            InitialFrameChecks.run();
            ResourceChecks.run();
            NamedBufferChecks.run(output);

            ArrayApiChecks.run();
            BufferTextureChecks.run(output);
            DivisorChecks.run(output);
            NamedTextureChecks.run(output);
            TextureLevelChecks.run(output);
            TextureReadbackChecks.run(output);
            TextureBindingChecks.run(output);
            ProgramBinaryChecks.run(output);
            FramebufferChecks.run(output);
            BlendChecks.run(output);
            SparseFramebufferChecks.run(output);
            LayerViewChecks.run(output);
            ProgramInterfaceChecks.run(output);
            ShortTextureChecks.run(output);
            FloatTextureChecks.run(output);
            IntegerVolumeChecks.run(output);
            HighTextureUnitChecks.run(output);
            BoundVolumeStorageChecks.run(output);
            BaseInstanceChecks.run(output);
            IndirectDrawChecks.run(output);
            SyncChecks.run(output);
            TimerQueryChecks.run(output);
            OcclusionQueryChecks.run(output);
            ClientPointerChecks.run();
            ViewChecks.run();
            ProgramResourceChecks.run();
            QuadChecks.run();
            MapQuadChecks.run();
            ShaderLinkChecks.run();
            ShaderLimitChecks.run(output);
            ManySamplerChecks.run(output);
            ShaderCompatibilityChecks.run();
            UniformInitializerChecks.run();
            CullingChecks.run();
            DepthRangeChecks.run();
            UiViewportChecks.run();
            Files.writeString(output.resolve("resource-checks.txt"), ResourceChecks.REPORT);

            if (window) {
                glBindFramebuffer(GL_FRAMEBUFFER, 0);
            } else {
                target = texture(width, height, null);
                framebuffer = glGenFramebuffers();
                glBindFramebuffer(GL_FRAMEBUFFER, framebuffer);
                glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, target, 0);
                int status = glCheckFramebufferStatus(GL_FRAMEBUFFER);
                if (status != GL_FRAMEBUFFER_COMPLETE) throw new IllegalStateException("Incomplete FBO: " + status);
            }
            StateChecks.run();
            DebugChecks.run();

            glViewport(0, 0, width, height);
            glDisable(GL_DEPTH_TEST);
            glDisable(GL_BLEND);
            glDisable(GL_CULL_FACE);
            glDisable(GL_SCISSOR_TEST);
            glClearColor(1, 0, 1, 1);
            source = sourceTexture();

            program = glCreateProgram();
            attach(program, GL_VERTEX_SHADER, "/shaders/adapter.vert");
            attach(program, GL_FRAGMENT_SHADER, "/shaders/adapter.frag");
            glLinkProgram(program);
            if (glGetProgrami(program, GL_LINK_STATUS) == 0)
                throw new IllegalStateException(glGetProgramInfoLog(program));
            glUseProgram(program);
            UniformChecks.run(program);

            long presents = DebugCounters.PRESENTS.sum();
            for (int frame = 0; frame < FRAMES; frame++) {
                if (frame != 0) {
                    backend.onFrameBegin();
                    frameOpen = true;
                }
                glClear(GL_COLOR_BUFFER_BIT);
                glDrawArrays(GL_TRIANGLES, 0, 3);
                backend.onFrameEnd();
                frameOpen = false;
                if (!window && !SDLGPU.SDL_WaitForGPUIdle(SDLGPUGate.device().getDevice())) {
                    throw new IllegalStateException("Cannot drain the offscreen frame");
                }
            }
            long presented = DebugCounters.PRESENTS.sum() - presents;
            if (window && presented != FRAMES) {
                throw new IllegalStateException("Expected " + FRAMES + " swapchain submissions; got " + presented);
            }
            verifyAndSave(output.resolve(window ? "window-texture.png" : "adapter-texture.png"), width, height);
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

    /** Uploads the pattern, then copies it into the sampled texture to exercise the image-copy alias. */
    private static int sourceTexture() {
        int edge = TexturePattern.EDGE;
        ByteBuffer upload = MemoryUtil.memAlloc(edge * edge * 4);
        int uploaded;
        try {
            for (int y = 0; y < edge; y++) {
                for (int x = 0; x < edge; x++) {
                    int color = TexturePattern.sourceArgb(x, y);
                    upload.put((byte) (color >>> 16))
                            .put((byte) (color >>> 8))
                            .put((byte) color)
                            .put((byte) 255);
                }
            }
            upload.flip();
            uploaded = texture(edge, edge, upload);
        } finally {
            MemoryUtil.memFree(upload);
        }
        int source = texture(edge, edge, null);
        try {
            GL44.glCopyImageSubData(
                    uploaded, GL_TEXTURE_2D, 0, 0, 0, 0, source, GL_TEXTURE_2D, 0, 0, 0, 0, edge, edge, 1);
        } finally {
            glDeleteTextures(uploaded);
        }
        return source;
    }

    private static void verifyAndSave(Path png, int width, int height) throws IOException {
        ByteBuffer pixels = MemoryUtil.memAlloc(width * height * 4);
        try {
            glReadPixels(0, 0, width, height, GL_RGBA, GL_UNSIGNED_BYTE, pixels);
            var image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    int offset = (y * width + x) * 4;
                    int actual = (pixels.get(offset + 3) & 255) << 24
                            | (pixels.get(offset) & 255) << 16
                            | (pixels.get(offset + 1) & 255) << 8
                            | pixels.get(offset + 2) & 255;
                    if (!TexturePattern.matchesArgb(actual, x, y, width, height)) {
                        throw new IllegalStateException("GLSM pixel " + x + "," + y + " in " + width + "x" + height
                                + " expected " + Integer.toHexString(TexturePattern.expectedArgb(x, y, width, height))
                                + ", got " + Integer.toHexString(actual));
                    }
                    // glReadPixels uses a bottom-left origin; PNG uses a top-left origin.
                    image.setRGB(x, height - 1 - y, actual);
                }
            }
            if (!ImageIO.write(image, "png", png.toFile())) throw new IOException("No PNG writer available");
        } finally {
            MemoryUtil.memFree(pixels);
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
            try (var stream = AdapterScene.class.getResourceAsStream(resource)) {
                if (stream == null) throw new IOException("Missing shader " + resource);
                glShaderSource(shader, new String(stream.readAllBytes(), StandardCharsets.UTF_8));
            }
            glCompileShader(shader);
            if (glGetShaderi(shader, GL_COMPILE_STATUS) == 0)
                throw new IllegalStateException(glGetShaderInfoLog(shader));
            glAttachShader(program, shader);
        } finally {
            glDeleteShader(shader);
        }
    }
}
