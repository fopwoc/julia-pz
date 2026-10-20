package dev.fopwoc.julia.backend.layer;

import static org.lwjgl.opengl.GL11.GL_TEXTURE_2D;
import static org.lwjgl.opengl.GL12.*;
import static org.lwjgl.opengl.GL30.*;

import dev.fopwoc.julia.backend.Upstream;
import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import org.lwjgl.system.MemoryUtil;

/** Framebuffer attachments with layer slices, draw/read buffer selection, clears, and readback guards. */
public abstract class FramebufferLayer extends BufferLayer {
    @Override
    public void bindFramebuffer(int target, int name) {
        super.bindFramebuffer(target, name);
        if (target != GL_READ_FRAMEBUFFER) subsystems().framebuffers().bound(Upstream.context().boundFboId);
    }

    @Override
    public void framebufferTexture2D(int target, int attachment, int textureTarget, int texture, int level) {
        if (texture != 0 && textureTarget != GL_TEXTURE_2D)
            throw new UnsupportedOperationException(
                    "FramebufferTexture2D texture target not implemented: " + textureTarget);
        subsystems()
                .framebuffers()
                .attach(subsystems().framebuffers().selected(target), attachment, texture, level, 0, false);
    }

    @Override
    public void framebufferTexture(int target, int attachment, int texture, int level) {
        subsystems()
                .framebuffers()
                .attach(subsystems().framebuffers().selected(target), attachment, texture, level, 0, false);
    }

    @Override
    public int checkFramebufferStatus(int target) {
        return subsystems().framebuffers().status(subsystems().framebuffers().selected(target), target);
    }

    @Override
    public void namedFramebufferTexture(int name, int attachment, int texture, int level) {
        subsystems().framebuffers().attach(name, attachment, texture, level, 0, false);
    }

    @Override
    public void namedFramebufferReadBuffer(int name, int buffer) {
        subsystems().framebuffers().readBuffer(name, buffer);
    }

    @Override
    public void namedFramebufferDrawBuffers(int name, IntBuffer buffers) {
        int[] values = new int[buffers.remaining()];
        for (int i = 0; i < values.length; i++) values[i] = buffers.get(buffers.position() + i);
        subsystems().framebuffers().drawBuffers(name, values);
    }

    @Override
    public void drawBuffer(int buffer) {
        drawBuffers(buffer);
    }

    @Override
    public void drawBuffers(int buffer) {
        var state = Upstream.context();
        if (state.boundFboId == 0 || state.boundFboId == state.defaultFboId) {
            super.drawBuffers(buffer);
            return;
        }
        subsystems().framebuffers().drawBuffers(state.boundFboId, new int[] {buffer});
    }

    @Override
    public void drawBuffers(IntBuffer buffers) {
        var state = Upstream.context();
        if (state.boundFboId == 0 || state.boundFboId == state.defaultFboId) {
            super.drawBuffers(buffers);
            return;
        }
        namedFramebufferDrawBuffers(state.boundFboId, buffers);
    }

    @Override
    public void readBuffer(int buffer) {
        var state = Upstream.context();
        if (state.boundReadFboId == 0 || state.boundReadFboId == state.defaultFboId) {
            super.readBuffer(buffer);
            return;
        }
        subsystems().framebuffers().readBuffer(state.boundReadFboId, buffer);
    }

    @Override
    public void clear(int mask) {
        subsystems().textureViews().flushSharedClears();
        var state = Upstream.context();
        subsystems().framebuffers().validateDraw(state);
        var fbo = Upstream.resources().getFbo(state.boundFboId);
        if (fbo != null && subsystems().framebuffers().sliced(fbo)) {
            subsystems().framebuffers().clearSlice(mask);
            return;
        }
        super.clear(mask);
    }

    @Override
    public void readPixels(int x, int y, int w, int h, int format, int type, ByteBuffer pixels) {
        subsystems().framebuffers().validateRead(format);
        super.readPixels(x, y, w, h, format, type, pixels);
    }

    @Override
    public void readPixels(int x, int y, int w, int h, int format, int type, FloatBuffer pixels) {
        subsystems().framebuffers().validateRead(format);
        super.readPixels(x, y, w, h, format, type, pixels);
    }

    @Override
    public void readPixels(int x, int y, int w, int h, int format, int type, IntBuffer pixels) {
        subsystems().framebuffers().validateRead(format);
        super.readPixels(x, y, w, h, format, type, pixels == null ? null : MemoryUtil.memByteBuffer(pixels));
    }

    @Override
    public void readPixels(int x, int y, int w, int h, int format, int type, long pixels) {
        subsystems().framebuffers().validateRead(format);
        super.readPixels(x, y, w, h, format, type, pixels);
    }

    @Override
    public void copyTexSubImage2D(int target, int level, int x, int y, int sx, int sy, int width, int height) {
        subsystems().framebuffers().requireFlatRead(Upstream.context().boundReadFboId);
        super.copyTexSubImage2D(target, level, x, y, sx, sy, width, height);
    }

    @Override
    public void copyTexImage2D(int target, int level, int format, int x, int y, int width, int height, int border) {
        subsystems().framebuffers().requireFlatRead(Upstream.context().boundReadFboId);
        super.copyTexImage2D(target, level, format, x, y, width, height, border);
    }

    @Override
    public void blitNamedFramebuffer(
            int read,
            int draw,
            int sx0,
            int sy0,
            int sx1,
            int sy1,
            int dx0,
            int dy0,
            int dx1,
            int dy1,
            int mask,
            int filter) {
        subsystems().framebuffers().requireFlatRead(read);
        subsystems().framebuffers().requireFlatRead(draw);
        super.blitNamedFramebuffer(read, draw, sx0, sy0, sx1, sy1, dx0, dy0, dx1, dy1, mask, filter);
    }

    @Override
    public void viewport(int x, int y, int width, int height) {
        super.viewport(x, y, width, height);
        // SDL still needs a clipping rectangle when GL scissor testing is disabled.
        // Upstream derives it from the viewport, so both native states must change together.
        var state = Upstream.context();
        if (!state.scissorEnabled) state.scissorDirty = true;
    }
}
