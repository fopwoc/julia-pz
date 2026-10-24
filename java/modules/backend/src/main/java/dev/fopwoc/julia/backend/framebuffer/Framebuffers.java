package dev.fopwoc.julia.backend.framebuffer;

import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL12.*;
import static org.lwjgl.opengl.GL30.*;
import static org.lwjgl.sdl.SDLGPU.*;

import com.gtnewhorizons.angelica.sdlgpu.SDLGPUGate;
import com.gtnewhorizons.angelica.sdlgpu.frame.ContextState;
import com.gtnewhorizons.angelica.sdlgpu.frame.FrameManager;
import com.gtnewhorizons.angelica.sdlgpu.pipeline.AttachmentClear;
import com.gtnewhorizons.angelica.sdlgpu.resource.FboState;
import com.gtnewhorizons.angelica.sdlgpu.resource.PixelOps;
import com.gtnewhorizons.angelica.sdlgpu.resource.ResourceManager;
import dev.fopwoc.julia.backend.Subsystems;
import dev.fopwoc.julia.backend.Upstream;
import dev.fopwoc.julia.backend.sdl.SdlSparseTargets;
import dev.fopwoc.julia.backend.texture.TextureStorage;
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
        // Texture storage generation at which the last draw found these attachments complete; 0 when unchecked.
        long complete;
    }

    private final WeakHashMap<FboState, Attachments> attachments = new WeakHashMap<>();
    // Every draw validates the bound framebuffer, which rarely changes between draws; skips the weak map lookup.
    private FboState lastFbo;
    private Attachments lastFields;

    private Attachments state(FboState fbo) {
        if (fbo == lastFbo) return lastFields;
        var fields = attachments.computeIfAbsent(fbo, ignored -> new Attachments());
        lastFbo = fbo;
        lastFields = fields;
        return fields;
    }

    public FboState named(int name) {
        var fbo = Upstream.resources().getFbo(name);
        if (name == 0 || fbo == null) throw new IllegalArgumentException("Named framebuffer does not exist: " + name);
        return fbo;
    }

    public int selected(int target) {
        var state = Upstream.context();
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
        var meta = texture == 0 ? null : Upstream.resources().getTextureMeta(texture);
        if (next != null) {
            if (!Subsystems.current().textureObjects().contains(texture) && meta == null)
                throw new IllegalArgumentException("Framebuffer texture does not exist: " + texture);
            if (level < 0 || layer < 0) throw new IllegalArgumentException("Negative framebuffer mip or slice");
            int target = Subsystems.current().textureObjects().target(texture);
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
            Upstream.clears()
                    .materializePendingClearForTexture(
                            Upstream.context(), Upstream.resources().getTextureHandle(previous.texture));
        if (name == Upstream.context().boundFboId) {
            Upstream.frames().endRenderPassIfActive(FrameManager.PASS_END_TARGET);
            // Which packed aspects are attached gates the pipeline's depth and stencil tests.
            Upstream.context().pipeline.markOutputDirty();
        }
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
            handle = Upstream.resources().ensureTextureUsage(texture, usage);
            if (handle == 0) throw new IllegalStateException("Cannot allocate framebuffer attachment usage");
            format = Upstream.resources().getTextureMeta(texture).sdlFormat();
            Upstream.resources().markFboAttachment(texture);
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
                fbo.depthTexture = Upstream.resources().getTextureHandle(active.texture);
                fbo.depthFormat = Upstream.resources().getTextureMeta(active.texture) == null
                        ? 0
                        : Upstream.resources().getTextureMeta(active.texture).sdlFormat();
            }
        }
        changed(name, fbo);
    }

    private static void validate(Slice slice, boolean color) {
        var meta = Upstream.resources().getTextureMeta(slice.texture);
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
        for (int slot : slots) if (slot < 0) SdlSparseTargets.requireSupported();
        if (Arrays.equals(fbo.drawBuffers, slots)) return;
        if (name == Upstream.context().boundFboId)
            Upstream.frames().endRenderPassIfActive(FrameManager.PASS_END_TARGET);
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
        state(fbo).complete = 0;
        fbo.targetsDirty = true;
        fbo.cachedFormatsDirty = true;
        fbo.cachedTargetsValid = false;
        recomputeDimensions(fbo);
        if (name == Upstream.context().boundFboId) bound(name);
    }

    private void recomputeDimensions(FboState fbo) {
        var fields = state(fbo);
        int width = Integer.MAX_VALUE, height = Integer.MAX_VALUE;
        for (var slice : fields.colors)
            if (slice != null) {
                var meta = Upstream.resources().getTextureMeta(slice.texture);
                if (meta != null) {
                    width = Math.min(width, Math.max(1, meta.width() >> Math.min(slice.level, 30)));
                    height = Math.min(height, Math.max(1, meta.height() >> Math.min(slice.level, 30)));
                }
            }
        var slice = fields.depth != null ? fields.depth : fields.stencil;
        if (slice != null) {
            var meta = Upstream.resources().getTextureMeta(slice.texture);
            if (meta != null) {
                width = Math.min(width, Math.max(1, meta.width() >> Math.min(slice.level, 30)));
                height = Math.min(height, Math.max(1, meta.height() >> Math.min(slice.level, 30)));
            }
        }
        fbo.width = width == Integer.MAX_VALUE ? 0 : width;
        fbo.height = height == Integer.MAX_VALUE ? 0 : height;
    }

    public void bound(int name) {
        var context = Upstream.context();
        if (name == 0 || name == context.defaultFboId) return;
        var fbo = named(name);
        recomputeDimensions(fbo);
        Upstream.clears().updatePipelineCacheColorFormats(context, fbo);
        context.pipeline.setDepthTargetFormat(fbo.depthTexture == 0 ? 0 : fbo.depthFormat);
        context.pipeline.markOutputDirty();
    }

    public int status(int name, int target) {
        if (target != GL_FRAMEBUFFER && target != GL_DRAW_FRAMEBUFFER && target != GL_READ_FRAMEBUFFER)
            throw new IllegalArgumentException("Invalid framebuffer status target");
        if (name == 0 || name == Upstream.context().defaultFboId) return GL_FRAMEBUFFER_COMPLETE;
        return status(named(name));
    }

    private int status(FboState fbo) {
        var fields = state(fbo);
        var resources = Upstream.resources();
        boolean present = false;
        for (var slice : fields.colors)
            if (slice != null) {
                present = true;
                var meta = allocated(resources, slice);
                if (meta == null || PixelOps.isDepthFormat(meta.sdlFormat()))
                    return GL_FRAMEBUFFER_INCOMPLETE_ATTACHMENT;
            }
        if (fields.depth != null) {
            present = true;
            var meta = allocated(resources, fields.depth);
            if (meta == null || !PixelOps.isDepthFormat(meta.sdlFormat())) return GL_FRAMEBUFFER_INCOMPLETE_ATTACHMENT;
        }
        if (fields.stencil != null) {
            present = true;
            var meta = allocated(resources, fields.stencil);
            if (meta == null || !PixelOps.isDepthStencilFormat(meta.sdlFormat()))
                return GL_FRAMEBUFFER_INCOMPLETE_ATTACHMENT;
        }
        return present ? GL_FRAMEBUFFER_COMPLETE : GL_FRAMEBUFFER_INCOMPLETE_MISSING_ATTACHMENT;
    }

    /** The slice's texture metadata when its level and layer are allocated, otherwise {@code null}. */
    private static ResourceManager.TextureMeta allocated(ResourceManager resources, Slice slice) {
        var meta = resources.getTextureMeta(slice.texture);
        boolean allocated = meta != null
                && resources.getTextureHandle(slice.texture) != 0
                && slice.level < meta.levels()
                && slice.layer
                        < (meta.glTarget() == GL_TEXTURE_3D
                                ? Math.max(1, meta.depth() >> Math.min(slice.level, 30))
                                : meta.depth());
        return allocated ? meta : null;
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
        var first = attachments.get(Upstream.resources().getFbo(a));
        var second = attachments.get(Upstream.resources().getFbo(b));
        if (first == null || second == null) return first == second;
        for (int i = 0; i < first.colors.length; i++) if (!sameSlice(first.colors[i], second.colors[i])) return false;
        return sameSlice(first.depth, second.depth) && sameSlice(first.stencil, second.stencil);
    }

    private static boolean sameSlice(Slice a, Slice b) {
        return a == null ? b == null : b != null && a.layer == b.layer;
    }

    public long begin(FrameManager frames, SDL_GPUColorTargetInfo.Buffer colors, SDL_GPUDepthStencilTargetInfo depth) {
        var fbo = named(Upstream.context().boundFboId);
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
        // Completeness changes only with attachments or texture storage, so repeated draws skip the per-slice lookups.
        long generation = TextureStorage.generation();
        if (fields.complete == generation) return;
        int status = status(fbo);
        if (status != GL_FRAMEBUFFER_COMPLETE)
            throw new IllegalStateException(
                    "Cannot render incomplete framebuffer: " + context.boundFboId + " status=" + status);
        fields.complete = generation;
    }

    /**
     * Aspects of a packed depth/stencil image that the bound framebuffer leaves unattached. SDL always targets both
     * aspects, so GL's "no such buffer" behaviour is emulated: the absent test passes and its writes and clears drop.
     */
    public int absentAspects(ContextState context) {
        if (context.boundFboId == 0 || context.boundFboId == context.defaultFboId) return 0;
        var fbo = Upstream.resources().getFbo(context.boundFboId);
        if (fbo == null || !PixelOps.isDepthStencilFormat(fbo.depthFormat)) return 0;
        var fields = state(fbo);
        return (fields.depth == null ? GL_DEPTH_BUFFER_BIT : 0) | (fields.stencil == null ? GL_STENCIL_BUFFER_BIT : 0);
    }

    public void validateRead(int format) {
        var context = Upstream.context();
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
        if (name != 0 && name != Upstream.context().defaultFboId && sliced(named(name)))
            throw new UnsupportedOperationException("Readback/blit of framebuffer slices is not implemented");
    }

    /** Whole-texture deferred clears cannot identify slices; encode the existing masked shader clear immediately. */
    public void clearSlice(int mask) {
        var state = Upstream.context();
        var fbo = Upstream.resources().getFbo(state.boundFboId);
        if (fbo.targetsDirty) fbo.recomputeTargets();
        bound(state.boundFboId);
        var clear = Upstream.attachmentClear();
        var target = clear.forFbo(fbo);
        boolean color = (mask & GL_COLOR_BUFFER_BIT) != 0 && (state.pipeline.colorWriteMask & 15) != 0;
        boolean depth = (mask & GL_DEPTH_BUFFER_BIT) != 0 && state.pipeline.depthWriteEnabled;
        boolean stencil = (mask & GL_STENCIL_BUFFER_BIT) != 0 && (state.pipeline.stencilWriteMask & 255) != 0;
        int coverage = clear.coverage(state, target);
        if (coverage == AttachmentClear.EMPTY) return;
        color &= fbo.hasAnyColor;
        depth &= fbo.depthTexture != 0;
        stencil &= fbo.depthTexture != 0 && PixelOps.isDepthStencilFormat(fbo.depthFormat);
        if (!color && !depth && !stencil) return;
        Upstream.pipeline().ensureRenderPass(state);
        var frame = Upstream.frames().frame();
        if (frame.renderPass == 0 || !clear.clearInPass(state, frame, target, color, depth, stencil))
            throw new UnsupportedOperationException("Cannot clear framebuffer slice with current masks and formats");
    }
}
