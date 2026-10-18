package dev.fopwoc.julia.probe.scene;

import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL15.*;
import static org.lwjgl.opengl.GL30.GL_TEXTURE_2D_ARRAY;
import static org.lwjgl.opengl.GL31.GL_COPY_WRITE_BUFFER;
import static org.lwjgl.opengl.GL43.GL_SHADER_STORAGE_BUFFER;
import static org.lwjgl.opengl.GL44.*;
import static org.lwjgl.opengl.GL45.glNamedBufferStorage;

import dev.fopwoc.julia.backend.BackendInternals;
import dev.fopwoc.julia.probe.adapter.ResourceReadback;
import java.nio.ByteBuffer;
import org.lwjgl.opengl.GL12;
import org.lwjgl.sdl.SDLGPU;
import org.lwjgl.system.MemoryStack;

/** Immutable buffer storage, texture clears, and array-layer copies, with raw SDL readback as the oracle. */
public final class ResourceChecks {
    private static final int WIDTH = 64;
    private static final int HEIGHT = 260;
    private static final int LAYERS = 2;
    public static final int VERIFIED_PIXELS = WIDTH * HEIGHT * LAYERS;
    static final String REPORT = "STAGE_PASS\nverified_resource_pixels=" + VERIFIED_PIXELS
            + "\nchecks=immutable buffer bytes,flags,bindings,reallocation rejection,tiled clears,array layers,"
            + "null clear,array-plane copies\n";

    private ResourceChecks() {}

    static void run() {
        buffers();
        textureLayers();
    }

    private static void buffers() {
        int previous = BackendInternals.context().boundCopyWriteBuffer;
        int previousSsbo = BackendInternals.context().boundSSBO;
        int buffer = glGenBuffers();
        int sentinel = glGenBuffers();
        try (var stack = MemoryStack.stackPush()) {
            glBindBuffer(GL_COPY_WRITE_BUFFER, sentinel);
            glBufferData(GL_COPY_WRITE_BUFFER, 4L, GL_DYNAMIC_DRAW);
            glBindBuffer(GL_SHADER_STORAGE_BUFFER, buffer);
            var data = stack.malloc(12);
            for (int i = 0; i < 12; i++) data.put(i, (byte) (i * 7));
            data.position(2).limit(10);
            glNamedBufferStorage(buffer, data, GL_DYNAMIC_STORAGE_BIT);
            if (data.position() != 2
                    || data.limit() != 10
                    || BackendInternals.context().boundCopyWriteBuffer != sentinel
                    || BackendInternals.context().boundSSBO != buffer)
                throw new IllegalStateException("Named storage changed caller state");
            glBindBuffer(GL_COPY_WRITE_BUFFER, buffer);
            if (glGetBufferParameteri(GL_COPY_WRITE_BUFFER, GL_BUFFER_SIZE) != 8
                    || glGetBufferParameteri(GL_COPY_WRITE_BUFFER, GL_BUFFER_IMMUTABLE_STORAGE) != GL_TRUE
                    || glGetBufferParameteri(GL_COPY_WRITE_BUFFER, GL_BUFFER_STORAGE_FLAGS) != GL_DYNAMIC_STORAGE_BIT) {
                throw new IllegalStateException("Named immutable storage metadata mismatch");
            }
            var output = stack.malloc(8);
            glGetBufferSubData(GL_COPY_WRITE_BUFFER, 0L, output);
            for (int i = 0; i < 8; i++)
                if (output.get(i) != (byte) ((i + 2) * 7))
                    throw new IllegalStateException("Named storage byte mismatch");
            boolean rejected = false;
            try {
                glNamedBufferStorage(buffer, data, GL_DYNAMIC_STORAGE_BIT);
            } catch (IllegalStateException expected) {
                rejected = true;
            }
            if (!rejected || glGetBufferParameteri(GL_COPY_WRITE_BUFFER, GL_BUFFER_SIZE) != 8)
                throw new IllegalStateException("Immutable storage was respecified");
        } finally {
            glBindBuffer(GL_SHADER_STORAGE_BUFFER, previousSsbo);
            glBindBuffer(GL_COPY_WRITE_BUFFER, previous);
            glDeleteBuffers(buffer);
            glDeleteBuffers(sentinel);
        }
    }

    private static void textureLayers() {
        int texture = glGenTextures();
        int plane = glGenTextures();
        try {
            glBindTexture(GL_TEXTURE_2D_ARRAY, texture);
            GL12.glTexImage3D(
                    GL_TEXTURE_2D_ARRAY, 0, GL_RGBA8, WIDTH, HEIGHT, LAYERS, 0, GL_RGBA, GL_UNSIGNED_BYTE, 0L);
            glClearTexSubImage(texture, 0, 0, 0, 0, WIDTH, HEIGHT, LAYERS, GL_RGBA, GL_FLOAT, new float[] {0, 0, 0, 1});
            glClearTexSubImage(texture, 0, 2, 257, 1, 3, 2, 1, GL_RGBA, GL_FLOAT, new float[] {0.2f, 0.4f, 0.6f, 1});
            glClearTexSubImage(texture, 0, 0, 0, 0, 1, 1, 1, GL_RGBA, GL_FLOAT, (float[]) null);
            boolean rejected = false;
            try {
                glClearTexSubImage(texture, 0, -1, 0, 0, 1, 1, 1, GL_RGBA, GL_FLOAT, new float[] {1, 1, 1, 1});
            } catch (IllegalArgumentException expected) {
                rejected = true;
            }
            if (!rejected) throw new IllegalStateException("Invalid clear region was accepted");
            glBindTexture(GL_TEXTURE_2D, plane);
            glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, 4, 4, 0, GL_RGBA, GL_UNSIGNED_BYTE, (ByteBuffer) null);
            glClearTexSubImage(plane, 0, 0, 0, 0, 4, 4, 1, GL_RGBA, GL_FLOAT, new float[] {0, 0, 0, 1});
            glCopyImageSubData(texture, GL_TEXTURE_2D_ARRAY, 0, 2, 257, 1, plane, GL_TEXTURE_2D, 0, 0, 0, 0, 3, 2, 1);
            glCopyImageSubData(plane, GL_TEXTURE_2D, 0, 0, 0, 0, texture, GL_TEXTURE_2D_ARRAY, 0, 10, 10, 0, 3, 2, 1);
            int format = BackendInternals.resources().getTextureMeta(texture).sdlFormat();
            boolean bgra = format == SDLGPU.SDL_GPU_TEXTUREFORMAT_B8G8R8A8_UNORM;
            // 0.2, 0.4, 0.6, 1.0 as normalized bytes, in storage channel order.
            byte[] changed =
                    bgra ? new byte[] {(byte) 153, 102, 51, (byte) 255} : new byte[] {51, 102, (byte) 153, (byte) 255};
            for (int layer = 0; layer < LAYERS; layer++) {
                byte[] pixels = ResourceReadback.layer(texture, layer);
                for (int y = 0; y < HEIGHT; y++) {
                    for (int x = 0; x < WIDTH; x++) {
                        boolean colored = layer == 1 && x >= 2 && x < 5 && y >= 257 && y < 259
                                || layer == 0 && x >= 10 && x < 13 && y >= 10 && y < 12;
                        for (int channel = 0; channel < 4; channel++) {
                            byte expected = colored
                                    ? changed[channel]
                                    : channel == 3 && !(layer == 0 && x == 0 && y == 0) ? (byte) 255 : 0;
                            if (pixels[(y * WIDTH + x) * 4 + channel] != expected) {
                                throw new IllegalStateException("Clear mismatch at layer=" + layer + " x=" + x + " y="
                                        + y + " channel=" + channel);
                            }
                        }
                    }
                }
            }
        } finally {
            glBindTexture(GL_TEXTURE_2D_ARRAY, 0);
            glBindTexture(GL_TEXTURE_2D, 0);
            glDeleteTextures(texture);
            glDeleteTextures(plane);
        }
    }
}
