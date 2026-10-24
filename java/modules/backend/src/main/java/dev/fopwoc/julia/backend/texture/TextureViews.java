package dev.fopwoc.julia.backend.texture;

import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL30.*;
import static org.lwjgl.opengl.GL43.*;
import static org.lwjgl.opengl.GL44.*;
import static org.lwjgl.sdl.SDLGPU.*;

import com.gtnewhorizons.angelica.sdlgpu.SDLGPUGate;
import com.gtnewhorizons.angelica.sdlgpu.frame.ContextState;
import com.gtnewhorizons.angelica.sdlgpu.resource.ResourceManager;
import com.gtnewhorizons.angelica.sdlgpu.sampler.SamplerLookup;
import dev.fopwoc.julia.backend.Subsystems;
import dev.fopwoc.julia.backend.Upstream;
import dev.fopwoc.julia.backend.sdl.SdlTextureViews;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;

/** Same-format 2D/array views share physical storage while retaining independent texture state. */
public final class TextureViews implements AutoCloseable {
    private final TextureTable textures;
    private final TextureMipSampling mips;
    // Live counts of flagged records, so per-draw and per-dispatch checks skip the common case.
    private int stencilTextures, viewTextures;

    private final Long2ObjectOpenHashMap<StencilSampling> stencil = new Long2ObjectOpenHashMap<>();
    // Consulted for every sampled texture and before every draw, so handles stay unboxed.
    private final Long2ObjectOpenHashMap<LongOpenHashSet> shared = new Long2ObjectOpenHashMap<>();
    private final LongArrayList pendingShared = new LongArrayList();
    private boolean resolvingSharedClears;

    public TextureViews(TextureTable textures) {
        this.textures = textures;
        mips = new TextureMipSampling(textures);
    }

    public void generated(int name) {
        textures.getOrCreate(name).fresh = true;
    }

    public void bound(int name) {
        var texture = textures.get(name);
        if (texture != null) texture.fresh = false;
    }

    public void immutable(int name) {
        textures.getOrCreate(name).immutable = true;
    }

    private static boolean immutable(TextureTable.Texture texture) {
        return texture != null && texture.immutable;
    }

    private static boolean stencil(TextureTable.Texture texture) {
        return texture != null && texture.stencil;
    }

    public void create(
            int name, int target, int original, int format, int minLevel, int levels, int minLayer, int layers) {
        var resources = Upstream.resources();
        var meta = resources.getTextureMeta(original);
        var destination = textures.get(name);
        var parent = textures.get(original);
        if (destination == null || !destination.fresh || name == original || resources.getTextureHandle(name) != 0) {
            throw new IllegalArgumentException("Texture view requires a fresh generated, unbound destination name");
        }
        if (!immutable(parent) || meta == null || resources.getTextureHandle(original) == 0) {
            throw new IllegalArgumentException("Texture view requires immutable source storage");
        }
        var sourceRange = parent.range != null ? parent.range : TextureViewRange.storage(meta);
        var range = sourceRange.subset(meta, target, format, minLevel, levels, minLayer, layers);
        long source = resources.getTextureHandle(original);
        // Resolve queued parent clears/uploads before exposing an independently addressed native handle.
        Upstream.flushTextureReferences();
        Upstream.clears().materializePendingClearForTexture(Upstream.context(), source);
        boolean whole2d = target == GL_TEXTURE_2D
                && meta.glTarget() == GL_TEXTURE_2D
                && minLevel == 0
                && range.levels() == meta.levels()
                && minLayer == 0;
        if (whole2d) {
            Upstream.aliasTexture(name, original);
        } else {
            int sdlType = target == GL_TEXTURE_2D ? SDL_GPU_TEXTURETYPE_2D : SDL_GPU_TEXTURETYPE_2D_ARRAY;
            long handle = SdlTextureViews.create(source, sdlType, minLevel, range.levels(), minLayer, range.layers());
            boolean registered = false;
            try {
                var viewMeta = new ResourceManager.TextureMeta(
                        target,
                        format,
                        meta.sdlFormat(),
                        Math.max(1, meta.width() >> minLevel),
                        Math.max(1, meta.height() >> minLevel),
                        range.layers(),
                        range.levels(),
                        meta.usage());
                Upstream.registerTextureView(name, handle, viewMeta);
                registered = true;
                var family = shared.computeIfAbsent(source, ignored -> new LongOpenHashSet());
                family.add(source);
                family.add(handle);
                shared.put(handle, family);
            } finally {
                if (!registered) SDL_ReleaseGPUTexture(SDLGPUGate.device().getDevice(), handle);
            }
        }
        destination.range = range;
        destination.fresh = false;
        destination.immutable = true;
        if (!destination.view) viewTextures++;
        destination.view = true;
    }

    public static void validate(
            ResourceManager.TextureMeta meta,
            int target,
            int format,
            int minLevel,
            int levels,
            int minLayer,
            int layers) {
        TextureViewRange.storage(meta).subset(meta, target, format, minLevel, levels, minLayer, layers);
    }

    /** Pending clears are keyed by SDL handle upstream; aliases must observe each other's writes in GL order. */
    public void sharedAccess(long handle) {
        if (shared.isEmpty() || resolvingSharedClears) return;
        var family = shared.get(handle);
        if (family == null) return;
        var state = Upstream.context();
        if (!pendingClears(state)) return;
        resolvingSharedClears = true;
        try {
            var peers = family.iterator();
            while (peers.hasNext()) {
                long peer = peers.nextLong();
                if (peer != handle) Upstream.clears().materializePendingClearForTexture(state, peer);
            }
        } finally {
            resolvingSharedClears = false;
        }
    }

    /**
     * Runs before every draw. Pending clears are far fewer than shared handles, and materializing a handle without one
     * does nothing, so only shared handles that have a pending clear are visited.
     */
    public void flushSharedClears() {
        if (shared.isEmpty() || resolvingSharedClears) return;
        var state = Upstream.context();
        if (!pendingClears(state)) return;
        pendingShared.clear();
        collectShared(state.pendingColorTextures);
        collectShared(state.pendingDepthTextures);
        collectShared(state.pendingStencilTextures);
        if (pendingShared.isEmpty()) return;
        resolvingSharedClears = true;
        try {
            for (int i = 0; i < pendingShared.size(); i++)
                Upstream.clears().materializePendingClearForTexture(state, pendingShared.getLong(i));
        } finally {
            resolvingSharedClears = false;
        }
    }

    private static boolean pendingClears(ContextState state) {
        return !state.pendingColorTextures.isEmpty()
                || !state.pendingDepthTextures.isEmpty()
                || !state.pendingStencilTextures.isEmpty();
    }

    // Collected first: materializing removes entries from the pending sets being walked.
    private void collectShared(LongSet pending) {
        var handles = pending.iterator();
        while (handles.hasNext()) {
            long handle = handles.nextLong();
            if (shared.containsKey(handle)) pendingShared.add(handle);
        }
    }

    public void beforeAllocation(int name) {
        if (immutable(textures.get(name))) throw new IllegalStateException("Cannot redefine immutable texture storage");
        mips.release(Upstream.resources().getTextureHandle(name));
    }

    public boolean parameter(int name, int parameter, int value) {
        if (mips.parameter(name, parameter, value)) return true;
        if (parameter != GL_DEPTH_STENCIL_TEXTURE_MODE) return false;
        if (value != GL_DEPTH_COMPONENT && value != GL_STENCIL_INDEX) {
            throw new IllegalArgumentException("Invalid depth/stencil texture mode");
        }
        if (value == GL_STENCIL_INDEX) {
            var meta = Upstream.resources().getTextureMeta(name);
            if (meta == null
                    || meta.glTarget() != GL_TEXTURE_2D
                    || meta.levels() != 1
                    || (meta.glFormat() != GL_DEPTH24_STENCIL8 && meta.glFormat() != GL_DEPTH32F_STENCIL8)) {
                throw new UnsupportedOperationException("Stencil sampling requires one-level 2D depth/stencil storage");
            }
        }
        var texture = textures.getOrCreate(name);
        boolean stencilMode = value == GL_STENCIL_INDEX;
        if (texture.stencil != stencilMode) stencilTextures += stencilMode ? 1 : -1;
        texture.stencil = stencilMode;
        Upstream.context().samplerBindGen++;
        return true;
    }

    public Integer parameter(int name, int parameter) {
        var mip = mips.parameter(name, parameter);
        if (mip != null) return mip;
        var meta = Upstream.resources().getTextureMeta(name);
        var texture = textures.get(name);
        var range = meta == null || !immutable(texture)
                ? null
                : texture.range != null ? texture.range : TextureViewRange.storage(meta);
        return switch (parameter) {
            case GL_DEPTH_STENCIL_TEXTURE_MODE -> stencil(texture) ? GL_STENCIL_INDEX : GL_DEPTH_COMPONENT;
            case GL_TEXTURE_IMMUTABLE_FORMAT -> immutable(texture) ? GL_TRUE : GL_FALSE;
            case GL_TEXTURE_IMMUTABLE_LEVELS -> range == null ? 0 : range.immutableLevels();
            case GL_TEXTURE_VIEW_MIN_LEVEL -> range == null ? 0 : range.minLevel();
            case GL_TEXTURE_VIEW_MIN_LAYER -> range == null ? 0 : range.minLayer();
            case GL_TEXTURE_VIEW_NUM_LEVELS -> range == null ? 0 : range.levels();
            case GL_TEXTURE_VIEW_NUM_LAYERS -> range == null ? 0 : range.layers();
            default -> null;
        };
    }

    public void beforeDelete(int name) {
        var resources = Upstream.resources();
        long handle = resources.getTextureHandle(name);
        if (handle != 0 && Upstream.frames().getCommandBuffer() != 0) {
            Upstream.clears().materializePendingClearForTexture(Upstream.context(), handle);
        }
    }

    public void deleted(int name, long handle) {
        if (handle != 0 && !Upstream.textureReferenced(Upstream.resources(), handle)) {
            mips.release(handle);
            var sampling = stencil.remove(handle);
            if (sampling != null) sampling.close();
            var family = shared.remove(handle);
            if (family != null) family.remove(handle);
        }
        var texture = textures.get(name);
        if (texture == null) return;
        if (texture.view) viewTextures--;
        if (texture.stencil) stencilTextures--;
        texture.mip = null;
        texture.range = null;
        texture.fresh = texture.immutable = texture.view = texture.stencil = false;
    }

    public void beforeCompute() {
        flushSharedClears();
        if (stencilTextures == 0) return;
        for (int name : Upstream.context().boundTextures) {
            if (stencil(textures.get(name))) {
                throw new UnsupportedOperationException(
                        "Compute dispatch with a bound stencil-aspect texture is unsupported");
            }
        }
    }

    public void prepare(ContextState state) {
        flushSharedClears();
        if (stencilTextures == 0) return;
        var program = Upstream.shaders().getProgram(state.boundProgram);
        if (program == null || !program.linked) return;
        var done = new LongOpenHashSet();
        for (boolean fragment : new boolean[] {false, true}) {
            int[] units = SamplerLookup.resolvedUnits(program, fragment);
            for (int slot = 0; slot < units.length; slot++) {
                int name = Subsystems.current().bufferTextures().samplerBinding(state, units[slot], slot, fragment);
                if (!stencil(textures.get(name))) continue;
                long handle = Upstream.resources().getTextureHandle(name);
                if (handle == 0 || !done.add(handle)) continue;
                var meta = Upstream.resources().getTextureMeta(name);
                stencil.computeIfAbsent(handle, ignored -> new StencilSampling())
                        .refresh(state, handle, meta);
            }
        }
    }

    public long samplingHandle(ResourceManager resources, int name) {
        long handle = resources.getTextureHandle(name);
        var texture = textures.get(name);
        if (!stencil(texture)) return mips.handle(resources, name, texture, handle);
        var sampling = stencil.get(handle);
        if (sampling == null) throw new IllegalStateException("Stencil view was not prepared before sampling");
        return sampling.texture();
    }

    @Override
    public void close() {
        if (viewTextures != 0 || mips.hasViews()) Upstream.flushTextureReferences();
        mips.close();
        for (var sampler : stencil.values()) sampler.close();
        stencil.clear();
        // Upstream shutdown releases one handle per name. Keep one name per physical allocation.
        if (viewTextures != 0) Upstream.deduplicateTextures();
        shared.clear();
        stencilTextures = viewTextures = 0;
    }
}
