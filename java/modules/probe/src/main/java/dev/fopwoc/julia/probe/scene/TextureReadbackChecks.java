package dev.fopwoc.julia.probe.scene;

import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL13.*;
import static org.lwjgl.opengl.GL15.*;
import static org.lwjgl.opengl.GL21.*;
import static org.lwjgl.opengl.GL30.*;
import static org.lwjgl.opengl.GL32.*;
import static org.lwjgl.opengl.GL43.glTextureView;
import static org.lwjgl.opengl.GL44.GL_CLIENT_STORAGE_BIT;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import org.lwjgl.opengl.ARBDirectStateAccess;
import org.lwjgl.opengl.GL45;
import org.lwjgl.opengl.GL45C;
import org.lwjgl.system.MemoryStack;

/** GPU-produced texture pixels reach named client and fenced pixel-pack destinations with exact offsets. */
final class TextureReadbackChecks {
    private TextureReadbackChecks() {}

    static void run(Path output) throws IOException {
        int texture = GL45.glCreateTextures(GL_TEXTURE_2D), sentinel = GL45.glCreateTextures(GL_TEXTURE_2D);
        int view = glGenTextures(), buffer = GL45.glCreateBuffers(), fbo = glGenFramebuffers();
        try (var stack = MemoryStack.stackPush()) {
            GL45.glTextureStorage2D(texture, 2, GL_RGBA8, 4, 2);
            GL45.glTextureStorage2D(sentinel, 1, GL_RGBA8, 1, 1);
            glActiveTexture(GL_TEXTURE0 + 37);
            glBindTexture(GL_TEXTURE_2D, sentinel);
            glBindFramebuffer(GL_FRAMEBUFFER, fbo);
            glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, texture, 0);
            glDisable(GL_SCISSOR_TEST);
            glColorMask(true, true, true, true);
            glClearColor(1, 0, 0, 1);
            glClear(GL_COLOR_BUFFER_BIT);
            glEnable(GL_SCISSOR_TEST);
            glScissor(2, 0, 2, 2);
            glClearColor(0, 1, 0, 1);
            glClear(GL_COLOR_BUFFER_BIT);
            glDisable(GL_SCISSOR_TEST);
            var client = stack.malloc(40);
            for (int i = 0; i < 40; i++) client.put(i, (byte) 90);
            client.position(4).limit(36);
            GL45.glGetTextureImage(texture, 0, GL_RGBA, GL_UNSIGNED_BYTE, client);
            if (client.position() != 4
                    || client.limit() != 36
                    || client.get(3) != 90
                    || client.duplicate().clear().get(36) != 90)
                throw new IllegalStateException("Named texture readback changed client state or canaries");
            verify(client, 4, false);
            var initial = stack.malloc(160);
            for (int i = 0; i < 160; i++) initial.put(i, (byte) 90);
            GL45.glNamedBufferStorage(buffer, initial, GL_CLIENT_STORAGE_BIT);
            glBindBuffer(GL_PIXEL_PACK_BUFFER, buffer);
            GL45C.glGetTextureImage(texture, 0, GL_RGBA, GL_UNSIGNED_BYTE, 32, 32L);
            reject(() -> GL45.glGetTextureImage(texture, 0, GL_RGBA, GL_UNSIGNED_BYTE, 31, 32L));
            reject(() -> GL45.glGetTextureImage(texture, 0, GL_RGBA, GL_UNSIGNED_BYTE, 32, 144L));
            waitForGpu();
            var packed = stack.malloc(160);
            GL45.glGetNamedBufferSubData(buffer, 0, packed);
            verify(packed, 32, false);
            for (int i = 0; i < 160; i++)
                if ((i < 32 || i >= 64) && packed.get(i) != 90)
                    throw new IllegalStateException("Pixel-pack download overwrote canary byte " + i);
            glClearColor(0, 0, 1, 1);
            glClear(GL_COLOR_BUFFER_BIT);
            ARBDirectStateAccess.glGetTextureImage(texture, 0, GL_RGBA, GL_UNSIGNED_BYTE, 32, 96L);
            waitForGpu();
            GL45C.glGetNamedBufferSubData(buffer, 0, packed);
            verify(packed, 32, false);
            verify(packed, 96, true);
            glBindBuffer(GL_PIXEL_PACK_BUFFER, 0);
            glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, texture, 1);
            glClear(GL_COLOR_BUFFER_BIT);
            glTextureView(view, GL_TEXTURE_2D, texture, GL_RGBA8, 1, 1, 0, 1);
            var mip = stack.malloc(8);
            ARBDirectStateAccess.glGetTextureImage(view, 0, GL_RGBA, GL_UNSIGNED_BYTE, mip);
            for (int i = 0; i < 8; i++)
                if ((mip.get(i) & 255) != (i % 4 >= 2 ? 255 : 0))
                    throw new IllegalStateException("View-relative mip readback mismatch");
            reject(() -> GL45.glGetTextureImage(
                    texture, 0, GL_RGBA, GL_UNSIGNED_BYTE, client.duplicate().limit(10)));
            reject(() -> GL45.glGetTextureImage(texture, 0, GL_RGBA, GL_UNSIGNED_BYTE, client.asReadOnlyBuffer()));
            reject(() -> GL45.glGetTextureImage(texture, 0, GL_RGB, GL_UNSIGNED_BYTE, client));
            if (glGetInteger(GL_ACTIVE_TEXTURE) != GL_TEXTURE0 + 37
                    || glGetInteger(GL_TEXTURE_BINDING_2D) != sentinel
                    || glGetTexLevelParameteri(GL_TEXTURE_2D, 0, GL_TEXTURE_WIDTH) != 1
                    || glGetInteger(GL_FRAMEBUFFER_BINDING) != fbo)
                throw new IllegalStateException("Named readback changed caller GL bindings");
            Files.writeString(
                    output.resolve("texture-readback.txt"),
                    "PASS\nverified_pixels=34\nchecks=GPU clears,client buffers,RGBA8 pixel-pack offsets,fence completion,named buffer reads,repeated writes,mip views,capacity rejection,canaries,binding preservation\n");
        } finally {
            glBindBuffer(GL_PIXEL_PACK_BUFFER, 0);
            glBindFramebuffer(GL_FRAMEBUFFER, 0);
            glDeleteFramebuffers(fbo);
            glDeleteBuffers(buffer);
            glDeleteTextures(view);
            glDeleteTextures(texture);
            glDeleteTextures(sentinel);
            glActiveTexture(GL_TEXTURE0);
        }
    }

    private static void verify(ByteBuffer values, int offset, boolean blue) {
        for (int i = 0; i < 8; i++) {
            boolean green = i % 4 >= 2;
            int[] expected = {blue || green ? 0 : 255, !blue && green ? 255 : 0, blue ? 255 : 0, 255};
            for (int c = 0; c < 4; c++)
                if ((values.get(offset + i * 4 + c) & 255) != expected[c])
                    throw new IllegalStateException("Texture readback pixel mismatch: " + offset + ":" + i + ":" + c);
        }
    }

    private static void waitForGpu() {
        long fence = glFenceSync(GL_SYNC_GPU_COMMANDS_COMPLETE, 0);
        try {
            int status = glClientWaitSync(fence, 0, 5_000_000_000L);
            if (status != GL_ALREADY_SIGNALED && status != GL_CONDITION_SATISFIED)
                throw new IllegalStateException("Pixel-pack fence did not complete");
        } finally {
            glDeleteSync(fence);
        }
    }

    private static void reject(Runnable operation) {
        try {
            operation.run();
        } catch (IllegalArgumentException | UnsupportedOperationException expected) {
            return;
        }
        throw new AssertionError("Invalid named texture readback accepted");
    }
}
