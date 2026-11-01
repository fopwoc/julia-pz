package dev.fopwoc.julia.probe.scene;

import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL13.*;
import static org.lwjgl.opengl.GL20.*;
import static org.lwjgl.opengl.GL30.*;

import dev.fopwoc.julia.probe.adapter.ResourceReadback;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.function.IntConsumer;
import org.lwjgl.system.MemoryStack;

/** Verifies an external renderer against a texture and a clipped, font-atlas-backed colored rectangle. */
public final class ImGuiScene {
    private ImGuiScene() {}

    public static void verify(IntConsumer draw) {
        int target = glGenTextures(), source = glGenTextures(), fbo = glGenFramebuffers();
        try (var stack = MemoryStack.stackPush()) {
            glBindTexture(GL_TEXTURE_2D, target);
            glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, 32, 32, 0, GL_RGBA, GL_UNSIGNED_BYTE, (ByteBuffer) null);
            glBindFramebuffer(GL_FRAMEBUFFER, fbo);
            glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, target, 0);
            if (glCheckFramebufferStatus(GL_FRAMEBUFFER) != GL_FRAMEBUFFER_COMPLETE)
                throw new IllegalStateException("ImGui probe framebuffer incomplete");
            glBindTexture(GL_TEXTURE_2D, source);
            var blue = stack.bytes((byte) 0, (byte) 0, (byte) 255, (byte) 255);
            glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, 1, 1, 0, GL_RGBA, GL_UNSIGNED_BYTE, blue);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
            glActiveTexture(GL_TEXTURE0);
            glViewport(0, 0, 32, 32);
            glDisable(GL_SCISSOR_TEST);
            glClearColor(0, 1, 0, 1);
            glClear(GL_COLOR_BUFFER_BIT);
            glViewport(3, 4, 9, 10);
            glScissor(1, 2, 3, 4);
            glEnable(GL_SCISSOR_TEST);
            glEnable(GL_DEPTH_TEST);
            glEnable(GL_CULL_FACE);
            glDisable(GL_BLEND);
            glUseProgram(0);
            glActiveTexture(GL_TEXTURE2);
            glBindTexture(GL_TEXTURE_2D, source);
            draw.accept(source);
            int[] viewport = new int[4], scissor = new int[4];
            glGetIntegerv(GL_VIEWPORT, viewport);
            glGetIntegerv(GL_SCISSOR_BOX, scissor);
            if (!Arrays.equals(viewport, new int[] {3, 4, 9, 10})
                    || !Arrays.equals(scissor, new int[] {1, 2, 3, 4})
                    || !glIsEnabled(GL_SCISSOR_TEST)
                    || !glIsEnabled(GL_DEPTH_TEST)
                    || !glIsEnabled(GL_CULL_FACE)
                    || glIsEnabled(GL_BLEND)
                    || glGetInteger(GL_CURRENT_PROGRAM) != 0
                    || glGetInteger(GL_ACTIVE_TEXTURE) != GL_TEXTURE2
                    || glGetInteger(GL_TEXTURE_BINDING_2D) != source)
                throw new IllegalStateException("ImGui renderer did not restore caller state");
            var pixels = ResourceReadback.layer(target, 0);
            for (int y = 0; y < 32; y++)
                for (int x = 0; x < 32; x++) {
                    boolean red = x >= 8 && x < 24 && 31 - y >= 6 && 31 - y < 22;
                    int offset = (y * 32 + x) * 4;
                    int r = pixels[offset] & 255, g = pixels[offset + 1] & 255;
                    int b = pixels[offset + 2] & 255, a = pixels[offset + 3] & 255;
                    if (r != (red ? 255 : 0) || g != 0 || b != (red ? 0 : 255) || a != 255)
                        throw new IllegalStateException(
                                "ImGui pixels differ at " + x + "," + y + ": " + r + "," + g + "," + b + "," + a);
                }
        } finally {
            glBindFramebuffer(GL_FRAMEBUFFER, 0);
            glDeleteFramebuffers(fbo);
            glDeleteTextures(target);
            glDeleteTextures(source);
        }
    }
}
