package dev.fopwoc.julia.probe.scene;

import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL30.*;
import static org.lwjgl.opengl.GL32.*;

import dev.fopwoc.julia.backend.Upstream;
import dev.fopwoc.julia.probe.adapter.FencedReadback;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.IntSupplier;
import org.lwjgl.opengl.ARBSync;

/** Downloads become CPU-readable through GL sync completion alone, with no device-idle wait. */
final class SyncChecks {
    private SyncChecks() {}

    static void run(Path output) throws IOException {
        Files.deleteIfExists(output.resolve("syncs.txt"));
        int texture = glGenTextures(), fbo = glGenFramebuffers();
        try {
            glBindTexture(GL_TEXTURE_2D, texture);
            glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, 4, 4, 0, GL_RGBA, GL_UNSIGNED_BYTE, (ByteBuffer) null);
            glBindFramebuffer(GL_FRAMEBUFFER, fbo);
            glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, texture, 0);
            glDisable(GL_SCISSOR_TEST);
            glColorMask(true, true, true, true);
            glClearColor(1, 0, 0, 1);
            glClear(GL_COLOR_BUFFER_BIT);
            invalid(() -> (int) glFenceSync(GL_SYNC_GPU_COMMANDS_COMPLETE, 1));
            for (int round = 0; round < 3; round++) {
                if (round != 0) {
                    glClearColor(0, 0, 1, 1);
                    glClear(GL_COLOR_BUFFER_BIT);
                }
                try (var download = new FencedReadback(texture)) {
                    long first = glFenceSync(GL_SYNC_GPU_COMMANDS_COMPLETE, 0);
                    long second = ARBSync.glFenceSync(GL_SYNC_GPU_COMMANDS_COMPLETE, 0);
                    if (!glIsSync(first) || !glIsSync(second) || first == second)
                        throw new IllegalStateException("Fence lifetime or identity is invalid");
                    int submits = Upstream.frames().frame().submitsThisFrame;
                    int poll = glClientWaitSync(second, GL_SYNC_FLUSH_COMMANDS_BIT, 0);
                    if (poll != GL_TIMEOUT_EXPIRED && poll != GL_ALREADY_SIGNALED && poll != GL_CONDITION_SATISFIED)
                        throw new IllegalStateException("Invalid fence polling result: " + poll);
                    if (Upstream.frames().frame().submitsThisFrame != submits)
                        throw new IllegalStateException("Polling submitted later commands");
                    invalid(() -> glClientWaitSync(first, 2, 0));
                    glDeleteSync(first); // Delete before a frame swap while another fence remains live.
                    if (glIsSync(first)) throw new IllegalStateException("Deleted fence remained live");
                    invalid(() -> glClientWaitSync(first, 0, 0));
                    int waited = glClientWaitSync(second, 0, round == 0 ? 5_000_000_000L : -1L);
                    if (waited != GL_ALREADY_SIGNALED && waited != GL_CONDITION_SATISFIED)
                        throw new IllegalStateException("GPU did not complete fenced download: " + waited);
                    var pixels = download.read();
                    for (int i = 0; i < pixels.length; i += 4)
                        if ((pixels[i] & 255) != (round == 0 ? 255 : 0)
                                || pixels[i + 1] != 0
                                || (pixels[i + 2] & 255) != (round == 0 ? 0 : 255)
                                || (pixels[i + 3] & 255) != 255)
                            throw new IllegalStateException("Fence did not cover GPU clear/download at pixel " + i / 4);
                    glDeleteSync(second);
                    invalid(() -> {
                        glDeleteSync(second);
                        return 0;
                    });
                    glDeleteSync(0);
                }
            }
            glClearColor(0, 1, 0, 1);
            glClear(GL_COLOR_BUFFER_BIT);
            try (var download = new FencedReadback(texture)) {
                glFlush();
                glFinish();
                var pixels = download.read();
                for (int i = 0; i < pixels.length; i += 4)
                    if (pixels[i] != 0
                            || (pixels[i + 1] & 255) != 255
                            || pixels[i + 2] != 0
                            || (pixels[i + 3] & 255) != 255)
                        throw new IllegalStateException("Finish left queued GPU work unsubmitted");
            }
            Files.writeString(
                    output.resolve("syncs.txt"),
                    "PASS\nverified_pixels=64\nchecks=native fence completion,finite and unsigned waits,polling without submission,independent lifetimes,deletion before swap,invalid flags and names,flush and finish\n");
        } finally {
            glBindFramebuffer(GL_FRAMEBUFFER, 0);
            glDeleteFramebuffers(fbo);
            glDeleteTextures(texture);
        }
    }

    private static void invalid(IntSupplier action) {
        try {
            action.getAsInt();
        } catch (IllegalArgumentException expected) {
            return;
        }
        throw new IllegalStateException("Invalid sync operation was accepted");
    }
}
