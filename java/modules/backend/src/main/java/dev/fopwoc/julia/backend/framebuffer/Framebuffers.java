package dev.fopwoc.julia.backend.framebuffer;

import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL12.*;
import static org.lwjgl.opengl.GL30.*;
import static org.lwjgl.sdl.SDLGPU.*;

import com.gtnewhorizons.angelica.sdlgpu.SDLGPUGate;
import com.gtnewhorizons.angelica.sdlgpu.frame.ContextState;
import com.gtnewhorizons.angelica.sdlgpu.frame.FrameManager;
import com.gtnewhorizons.angelica.sdlgpu.resource.FboState;
import com.gtnewhorizons.angelica.sdlgpu.resource.PixelOps;
import dev.fopwoc.julia.backend.BackendInternals;
import dev.fopwoc.julia.backend.platform.Platform;
import java.util.Arrays;
import java.util.WeakHashMap;
import org.lwjgl.sdl.SDL_GPUColorTargetInfo;
import org.lwjgl.sdl.SDL_GPUDepthStencilTargetInfo;

/** Named framebuffer state and single-slice attachments; logical bindings never change during named operations. */
public final class Framebuffers {
    private record Slice(int texture, int level, int layer, boolean sliced) {}

    private static final class Attachments {
        final Slice[] colors = new Slice[ContextState.MAX_COLOR_ATTACHMENTS];
        Slice depth, stencil;
    }

    private final WeakHashMap<FboState, Attachments> attachments = new WeakHashMap<>();

    private Attachments state(FboState fbo) {
        return attachments.computeIfAbsent(fbo, ignored -> new Attachments());
    }

    public FboState named(int name) {
        var fbo = BackendInternals.resources().getFbo(name);
        if (name == 0 || fbo == null) throw new IllegalArgumentException("Named framebuffer does not exist: " + name);
        return fbo;
    }

    public int selected(int target) {
        var state = BackendInternals.context();
        return switch (target) {
            case GL_FRAMEBUFFER, GL_DRAW_FRAMEBUFFER -> state.boundFboId;
            case GL_READ_FRAMEBUFFER -> state.boundReadFboId;
            default -> throw new IllegalArgumentException("Invalid framebuffer target: " + target);
        };
    }

    public void attach(int name, int attachment, int texture, int level, int layer, boolean sliced) {
        FboState fbo = named(name);
        var fields = state(fbo);
        int color = attachment - GL_COLOR_ATTACHMENT0;
        if ((color < 0 || color >= fields.colors.length)
                && attachment != GL_DEPTH_ATTACHMENT
                && attachment != GL_STENCIL_ATTACHMENT
                && attachment != GL_DEPTH_STENCIL_ATTACHMENT)
            throw new IllegalArgumentException("Invalid framebuffer attachment: " + attachment);
        Slice next = texture == 0 ? null : new Slice(texture, level, layer, sliced);
        var meta = texture == 0 ? null : BackendInternals.resources().getTextureMeta(texture);
        if (next != null) {
            if (!BackendInternals.textureObjects().contains(texture) && meta == null)
                throw new IllegalArgumentException("Framebuffer texture does not exist: " + texture);
            if (level < 0 || layer < 0) throw new IllegalArgumentException("Negative framebuffer mip or slice");
            int target = BackendInternals.textureObjects().target(texture);
            if (sliced ? target != GL_TEXTURE_2D_ARRAY && target != GL_TEXTURE_3D : target != GL_TEXTURE_2D)
                throw new UnsupportedOperationException(
                        "Framebuffer attachment target not implemented: " + target + " single slice=" + sliced);
            if (meta != null) validate(next, color >= 0 && color < fields.colors.length);
        }
        Slice depth =
                attachment == GL_DEPTH_ATTACHMENT || attachment == GL_DEPTH_STENCIL_ATTACHMENT ? next : fields.depth;
        Slice stencil = attachment == GL_STENCIL_ATTACHMENT || attachment == GL_DEPTH_STENCIL_ATTACHMENT
                ? next
                : fields.stencil;
        if (depth != null && stencil != null && !depth.equals(stencil))
            throw new UnsupportedOperationException(
                    "Separate depth and stencil framebuffer images are not implemented");
        Slice previous = color >= 0 && color < fields.colors.length
                ? fields.colors[color]
                : fields.depth != null ? fields.depth : fields.stencil;
        if (previous != null)
            BackendInternals.clears()
                    .materializePendingClearForTexture(
                            BackendInternals.context(),
                            BackendInternals.resources().getTextureHandle(previous.texture));
        if (name == BackendInternals.context().boundFboId)
            BackendInternals.frames().endRenderPassIfActive(FrameManager.PASS_END_TARGET);
        long handle = 0;
        int format = 0;
        if (meta != null) {
            int usage = color >= 0 && color < fields.colors.length
                    ? SDL_GPU_TEXTUREUSAGE_COLOR_TARGET
                    : SDL_GPU_TEXTUREUSAGE_DEPTH_STENCIL_TARGET;
            int type = meta.glTarget() == GL_TEXTURE_2D
                    ? SDL_GPU_TEXTURETYPE_2D
                    : meta.glTarget() == GL_TEXTURE_3D ? SDL_GPU_TEXTURETYPE_3D : SDL_GPU_TEXTURETYPE_2D_ARRAY;
            if (!SDL_GPUTextureSupportsFormat(
                    SDLGPUGate.device().getDevice(), meta.sdlFormat(), type, meta.usage() | usage))
                throw new UnsupportedOperationException(
                        "Framebuffer attachment usage is unsupported for this texture format and target");
            handle = BackendInternals.resources().ensureTextureUsage(texture, usage);
            if (handle == 0) throw new IllegalStateException("Cannot allocate framebuffer attachment usage");
            format = BackendInternals.resources().getTextureMeta(texture).sdlFormat();
            BackendInternals.resources().markFboAttachment(texture);
        }
        if (color >= 0 && color < fields.colors.length) {
            fields.colors[color] = next;
            fbo.colorTextures[color] = handle;
            fbo.colorGlIds[color] = texture;
            fbo.colorLevels[color] = texture == 0 ? 0 : level;
            fbo.colorFormats[color] = format;
            fbo.colorAttachmentCount = 0;
            for (int i = 0; i < fields.colors.length; i++)
                if (fields.colors[i] != null) fbo.colorAttachmentCount = i + 1;
        } else {
            fields.depth = depth;
            fields.stencil = stencil;
            Slice active = depth != null ? depth : stencil;
            fbo.depthGlId = active == null ? 0 : active.texture;
            fbo.depthLevel = active == null ? 0 : active.level;
            if (active == null) {
                fbo.depthTexture = 0;
                fbo.depthFormat = 0;
            } else {
                fbo.depthTexture = BackendInternals.resources().getTextureHandle(active.texture);
                fbo.depthFormat = BackendInternals.resources().getTextureMeta(active.texture) == null
                        ? 0
                        : BackendInternals.resources()
                                .getTextureMeta(active.texture)
                                .sdlFormat();
            }
        }
        changed(name, fbo);
    }

    private static void validate(Slice slice, boolean color) {
        var meta = BackendInternals.resources().getTextureMeta(slice.texture);
        if (meta == null) return;
        int layers = meta.glTarget() == GL_TEXTURE_3D
                ? Math.max(1, meta.depth() >> Math.min(slice.level, 30))
                : meta.depth();
        if (slice.level >= meta.levels() || slice.layer >= layers)
            throw new IllegalArgumentException("Framebuffer mip or slice outside texture storage");
        boolean depth = PixelOps.isDepthFormat(meta.sdlFormat());
        if (color == depth)
            throw new IllegalArgumentException("Framebuffer attachment aspect does not match texture format");
        if (!color && (meta.glTarget() == GL_TEXTURE_3D || slice.layer > 255))
            throw new UnsupportedOperationException("SDL depth attachment requires a 2D/array slice below 256");
    }

    public void drawBuffers(int name, int[] buffers) {
        FboState fbo = named(name);
        if (buffers.length > ContextState.MAX_COLOR_ATTACHMENTS)
            throw new IllegalArgumentException("Too many framebuffer draw buffers");
        int[] slots = new int[buffers.length];
        boolean any = false;
        int used = 0;
        for (int i = 0; i < slots.length; i++) {
            int index = colorIndex(buffers[i]);
            slots[i] = index;
            if (index >= 0) {
                if ((used & 1 << index) != 0)
                    throw new IllegalArgumentException("Duplicate framebuffer draw attachment");
                used |= 1 << index;
                any = true;
            }
        }
        if (!any) slots = new int[0];
        for (int slot : slots) if (slot < 0) Platform.current().requireSparseColorTargets();
        if (Arrays.equals(fbo.drawBuffers, slots)) return;
        if (name == BackendInternals.context().boundFboId)
            BackendInternals.frames().endRenderPassIfActive(FrameManager.PASS_END_TARGET);
        fbo.drawBuffers = slots;
        changed(name, fbo);
    }

    public void readBuffer(int name, int buffer) {
        named(name).readBufferIndex = colorIndex(buffer);
    }

    private static int colorIndex(int buffer) {
        if (buffer == GL_NONE) return -1;
        int value = buffer - GL_COLOR_ATTACHMENT0;
        if (value < 0 || value >= ContextState.MAX_COLOR_ATTACHMENTS)
            throw new IllegalArgumentException("Invalid framebuffer color buffer: " + buffer);
        return value;
    }

    private void changed(int name, FboState fbo) {
        fbo.targetsDirty = true;
        fbo.cachedFormatsDirty = true;
        fbo.cachedTargetsValid = false;
        recomputeDimensions(fbo);
        if (name == BackendInternals.context().boundFboId) bound(name);
    }

    private void recomputeDimensions(FboState fbo) {
        var fields = state(fbo);
        int width = Integer.MAX_VALUE, height = Integer.MAX_VALUE;
        for (var slice : fields.colors)
            if (slice != null) {
                var meta = BackendInternals.resources().getTextureMeta(slice.texture);
                if (meta != null) {
                    width = Math.min(width, Math.max(1, meta.width() >> Math.min(slice.level, 30)));
                    height = Math.min(height, Math.max(1, meta.height() >> Math.min(slice.level, 30)));
                }
            }
        var slice = fields.depth != null ? fields.depth : fields.stencil;
        if (slice != null) {
            var meta = BackendInternals.resources().getTextureMeta(slice.texture);
            if (meta != null) {
                width = Math.min(width, Math.max(1, meta.width() >> Math.min(slice.level, 30)));
                height = Math.min(height, Math.max(1, meta.height() >> Math.min(slice.level, 30)));
            }
        }
        fbo.width = width == Integer.MAX_VALUE ? 0 : width;
        fbo.height = height == Integer.MAX_VALUE ? 0 : height;
    }

    public void bound(int name) {
        var context = BackendInternals.context();
        if (name == 0 || name == context.defaultFboId) return;
        var fbo = named(name);
        recomputeDimensions(fbo);
        BackendInternals.clears().updatePipelineCacheColorFormats(context, fbo);
        context.pipeline.setDepthTargetFormat(fbo.depthTexture == 0 ? 0 : fbo.depthFormat);
        context.pipeline.markOutputDirty();
    }

    public int status(int name, int target) {
        if (target != GL_FRAMEBUFFER && target != GL_DRAW_FRAMEBUFFER && target != GL_READ_FRAMEBUFFER)
            throw new IllegalArgumentException("Invalid framebuffer status target");
        if (name == 0 || name == BackendInternals.context().defaultFboId) return GL_FRAMEBUFFER_COMPLETE;
        var fbo = named(name);
        var fields = state(fbo);
        boolean present = false;
        for (var slice : fields.colors)
            if (slice != null) {
                present = true;
                if (!allocated(slice)
                        || PixelOps.isDepthFormat(BackendInternals.resources()
                                .getTextureMeta(slice.texture)
                                .sdlFormat())) return GL_FRAMEBUFFER_INCOMPLETE_ATTACHMENT;
            }
        if (fields.depth != null) {
            present = true;
            if (!allocated(fields.depth)
                    || !PixelOps.isDepthFormat(BackendInternals.resources()
                            .getTextureMeta(fields.depth.texture)
                            .sdlFormat())) return GL_FRAMEBUFFER_INCOMPLETE_ATTACHMENT;
        }
        if (fields.stencil != null) {
            present = true;
            if (!allocated(fields.stencil)
                    || !PixelOps.isDepthStencilFormat(BackendInternals.resources()
                            .getTextureMeta(fields.stencil.texture)
                            .sdlFormat())) return GL_FRAMEBUFFER_INCOMPLETE_ATTACHMENT;
        }
        return present ? GL_FRAMEBUFFER_COMPLETE : GL_FRAMEBUFFER_INCOMPLETE_MISSING_ATTACHMENT;
    }

    private static boolean allocated(Slice slice) {
        var meta = BackendInternals.resources().getTextureMeta(slice.texture);
        return meta != null
                && BackendInternals.resources().getTextureHandle(slice.texture) != 0
                && slice.level < meta.levels()
                && slice.layer
                        < (meta.glTarget() == GL_TEXTURE_3D
                                ? Math.max(1, meta.depth() >> Math.min(slice.level, 30))
                                : meta.depth());
    }

    public boolean sliced(FboState fbo) {
        var fields = attachments.get(fbo);
        if (fields == null) return false;
        for (var slice : fields.colors) if (slice != null && slice.sliced) return true;
        return fields.depth != null && fields.depth.sliced || fields.stencil != null && fields.stencil.sliced;
    }

    public void layout(FboState fbo) {
        var fields = attachments.get(fbo);
        if (fields == null) return;
        long hash = fbo.structuralLayoutHash;
        for (var slice : fields.colors) hash = hash * 31 + (slice == null ? 0 : slice.layer);
        var depth = fields.depth != null ? fields.depth : fields.stencil;
        hash = hash * 31 + (depth == null ? 0 : depth.layer);
        fbo.structuralLayoutHash =
                hash == 0 || hash == FrameManager.FBO0_LAYOUT_HASH ? hash ^ 0x6a09e667f3bcc909L : hash;
    }

    public boolean sameSlices(int a, int b) {
        var first = attachments.get(BackendInternals.resources().getFbo(a));
        var second = attachments.get(BackendInternals.resources().getFbo(b));
        if (first == null || second == null) return first == second;
        for (int i = 0; i < first.colors.length; i++) if (!sameSlice(first.colors[i], second.colors[i])) return false;
        return sameSlice(first.depth, second.depth) && sameSlice(first.stencil, second.stencil);
    }

    private static boolean sameSlice(Slice a, Slice b) {
        return a == null ? b == null : b != null && a.layer == b.layer;
    }

    public long begin(FrameManager frames, SDL_GPUColorTargetInfo.Buffer colors, SDL_GPUDepthStencilTargetInfo depth) {
        var fbo = named(BackendInternals.context().boundFboId);
        var fields = state(fbo);
        if (colors != null)
            for (int i = 0; i < colors.remaining(); i++) {
                int index = fbo.drawBuffers[i];
                var slice = index < 0 ? null : fields.colors[index];
                if (slice != null) validate(slice, true);
                colors.get(i).layer_or_depth_plane(slice == null ? 0 : slice.layer);
            }
        var slice = fields.depth != null ? fields.depth : fields.stencil;
        if (depth != null) {
            if (slice != null) validate(slice, false);
            depth.layer((byte) (slice == null ? 0 : slice.layer));
        }
        long pass = frames.beginRenderPass(colors, depth);
        // Leading GL_NONE slots must not prevent reuse of an otherwise identical open pass.
        if (fbo.hasAnyColor) frames.frame().currentColorTarget = fbo.primaryTarget;
        return pass;
    }

    public void validateDraw(ContextState context) {
        if (context.boundFboId == 0 || context.boundFboId == context.defaultFboId) return;
        var fbo = named(context.boundFboId);
        var fields = state(fbo);
        if (PixelOps.isDepthStencilFormat(fbo.depthFormat) && (fields.depth == null || fields.stencil == null))
            throw new UnsupportedOperationException(
                    "Rendering a single aspect of a packed depth/stencil framebuffer attachment is not implemented");
        int status = status(context.boundFboId, GL_DRAW_FRAMEBUFFER);
        if (status != GL_FRAMEBUFFER_COMPLETE)
            throw new IllegalStateException(
                    "Cannot render incomplete framebuffer: " + context.boundFboId + " status=" + status);
    }

    public void validateRead(int format) {
        var context = BackendInternals.context();
        int name = context.boundReadFboId;
        if (name == 0 || name == context.defaultFboId) return;
        var fbo = named(name);
        requireFlatRead(name);
        if (format != GL_DEPTH_COMPONENT
                && format != GL_STENCIL_INDEX
                && format != GL_DEPTH_STENCIL
                && fbo.readBufferIndex < 0) throw new IllegalStateException("Framebuffer color read buffer is GL_NONE");
    }

    public void requireFlatRead(int name) {
        if (name != 0 && name != BackendInternals.context().defaultFboId && sliced(named(name)))
            throw new UnsupportedOperationException("Readback/blit of framebuffer slices is not implemented");
    }
}
