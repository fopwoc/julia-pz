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
import dev.fopwoc.julia.backend.BackendInternals;
import dev.fopwoc.julia.backend.platform.Platform;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** Same-format 2D/array views share physical storage while retaining independent texture state. */
public final class TextureViews implements AutoCloseable {
    private final Set<Integer> fresh = new HashSet<>();
    private final Set<Integer> immutable = new HashSet<>();
    private final Map<Integer, Integer> modes = new HashMap<>();
    private final Set<Integer> views = new HashSet<>();
    private final Map<Long, StencilSampling> stencil = new HashMap<>();
    private final Map<Integer, TextureViewRange> ranges = new HashMap<>();
    private final Map<Long, Set<Long>> shared = new HashMap<>();
    private boolean resolvingSharedClears;

    public void generated(int name) {
        fresh.add(name);
    }

    public void bound(int name) {
        fresh.remove(name);
    }

    public void immutable(int name) {
        immutable.add(name);
    }

    public void create(
            int name, int target, int original, int format, int minLevel, int levels, int minLayer, int layers) {
        var resources = BackendInternals.resources();
        var meta = resources.getTextureMeta(original);
        if (!fresh.contains(name) || name == original || resources.getTextureHandle(name) != 0) {
            throw new IllegalArgumentException("Texture view requires a fresh generated, unbound destination name");
        }
        if (!immutable.contains(original) || meta == null || resources.getTextureHandle(original) == 0) {
            throw new IllegalArgumentException("Texture view requires immutable source storage");
        }
        var sourceRange = ranges.getOrDefault(original, TextureViewRange.storage(meta));
        var range = sourceRange.subset(meta, target, format, minLevel, levels, minLayer, layers);
        long source = resources.getTextureHandle(original);
        // Resolve queued parent clears/uploads before exposing an independently addressed native handle.
        BackendInternals.flushTextureReferences();
        BackendInternals.clears().materializePendingClearForTexture(BackendInternals.context(), source);
        boolean whole2d = target == GL_TEXTURE_2D
                && meta.glTarget() == GL_TEXTURE_2D
                && minLevel == 0
                && range.levels() == meta.levels()
                && minLayer == 0;
        if (whole2d) {
            BackendInternals.aliasTexture(name, original);
        } else {
            int sdlType = target == GL_TEXTURE_2D ? SDL_GPU_TEXTURETYPE_2D : SDL_GPU_TEXTURETYPE_2D_ARRAY;
            long handle = Platform.current()
                    .createTextureView(source, sdlType, minLevel, range.levels(), minLayer, range.layers());
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
                BackendInternals.registerTextureView(name, handle, viewMeta);
                registered = true;
                var family = shared.computeIfAbsent(source, ignored -> new HashSet<>());
                family.add(source);
                family.add(handle);
                shared.put(handle, family);
            } finally {
                if (!registered) SDL_ReleaseGPUTexture(SDLGPUGate.device().getDevice(), handle);
            }
        }
        ranges.put(name, range);
        fresh.remove(name);
        immutable.add(name);
        views.add(name);
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
        var family = shared.get(handle);
        if (family == null || resolvingSharedClears) return;
        resolvingSharedClears = true;
        try {
            for (long peer : family)
                if (peer != handle)
                    BackendInternals.clears().materializePendingClearForTexture(BackendInternals.context(), peer);
        } finally {
            resolvingSharedClears = false;
        }
    }

    public void flushSharedClears() {
        if (shared.isEmpty() || resolvingSharedClears) return;
        resolvingSharedClears = true;
        try {
            for (long handle : shared.keySet())
                BackendInternals.clears().materializePendingClearForTexture(BackendInternals.context(), handle);
        } finally {
            resolvingSharedClears = false;
        }
    }

    public void beforeAllocation(int name) {
        if (immutable.contains(name)) throw new IllegalStateException("Cannot redefine immutable texture storage");
    }

    public boolean parameter(int name, int parameter, int value) {
        if (parameter != GL_DEPTH_STENCIL_TEXTURE_MODE) return false;
        if (value != GL_DEPTH_COMPONENT && value != GL_STENCIL_INDEX) {
            throw new IllegalArgumentException("Invalid depth/stencil texture mode");
        }
        if (value == GL_STENCIL_INDEX) {
            var meta = BackendInternals.resources().getTextureMeta(name);
            if (meta == null
                    || meta.glTarget() != GL_TEXTURE_2D
                    || meta.levels() != 1
                    || (meta.glFormat() != GL_DEPTH24_STENCIL8 && meta.glFormat() != GL_DEPTH32F_STENCIL8)) {
                throw new UnsupportedOperationException("Stencil sampling requires one-level 2D depth/stencil storage");
            }
        }
        modes.put(name, value);
        BackendInternals.context().samplerBindGen++;
        return true;
    }

    public Integer parameter(int name, int parameter) {
        var meta = BackendInternals.resources().getTextureMeta(name);
        var range = meta == null || !immutable.contains(name)
                ? null
                : ranges.getOrDefault(name, TextureViewRange.storage(meta));
        return switch (parameter) {
            case GL_DEPTH_STENCIL_TEXTURE_MODE -> modes.getOrDefault(name, GL_DEPTH_COMPONENT);
            case GL_TEXTURE_IMMUTABLE_FORMAT -> immutable.contains(name) ? GL_TRUE : GL_FALSE;
            case GL_TEXTURE_IMMUTABLE_LEVELS -> range == null ? 0 : range.immutableLevels();
            case GL_TEXTURE_VIEW_MIN_LEVEL -> range == null ? 0 : range.minLevel();
            case GL_TEXTURE_VIEW_MIN_LAYER -> range == null ? 0 : range.minLayer();
            case GL_TEXTURE_VIEW_NUM_LEVELS -> range == null ? 0 : range.levels();
            case GL_TEXTURE_VIEW_NUM_LAYERS -> range == null ? 0 : range.layers();
            default -> null;
        };
    }

    public void beforeDelete(int name) {
        var resources = BackendInternals.resources();
        long handle = resources.getTextureHandle(name);
        if (handle != 0 && BackendInternals.frames().getCommandBuffer() != 0) {
            BackendInternals.clears().materializePendingClearForTexture(BackendInternals.context(), handle);
        }
    }

    public void deleted(int name, long handle) {
        if (handle != 0 && !BackendInternals.textureReferenced(BackendInternals.resources(), handle)) {
            var sampling = stencil.remove(handle);
            if (sampling != null) sampling.close();
            var family = shared.remove(handle);
            if (family != null) family.remove(handle);
        }
        ranges.remove(name);
        fresh.remove(name);
        immutable.remove(name);
        views.remove(name);
        modes.remove(name);
    }

    public void beforeCompute() {
        flushSharedClears();
        if (!modes.containsValue(GL_STENCIL_INDEX)) return;
        for (int name : BackendInternals.context().boundTextures) {
            if (modes.getOrDefault(name, GL_DEPTH_COMPONENT) == GL_STENCIL_INDEX) {
                throw new UnsupportedOperationException(
                        "Compute dispatch with a bound stencil-aspect texture is unsupported");
            }
        }
    }

    public void prepare(ContextState state) {
        flushSharedClears();
        if (!modes.containsValue(GL_STENCIL_INDEX)) return;
        var program = BackendInternals.shaders().getProgram(state.boundProgram);
        if (program == null || !program.linked) return;
        var done = new HashSet<Long>();
        for (boolean fragment : new boolean[] {false, true}) {
            int[] units = SamplerLookup.resolvedUnits(program, fragment);
            for (int slot = 0; slot < units.length; slot++) {
                int name = BackendInternals.bufferTextures().samplerBinding(state, units[slot], slot, fragment);
                if (modes.getOrDefault(name, GL_DEPTH_COMPONENT) != GL_STENCIL_INDEX) continue;
                long handle = BackendInternals.resources().getTextureHandle(name);
                if (handle == 0 || !done.add(handle)) continue;
                var meta = BackendInternals.resources().getTextureMeta(name);
                stencil.computeIfAbsent(handle, ignored -> new StencilSampling())
                        .refresh(state, handle, meta);
            }
        }
    }

    public long samplingHandle(int name) {
        var resources = BackendInternals.resources();
        long handle = resources.getTextureHandle(name);
        if (modes.getOrDefault(name, GL_DEPTH_COMPONENT) != GL_STENCIL_INDEX) return handle;
        var sampling = stencil.get(handle);
        if (sampling == null) throw new IllegalStateException("Stencil view was not prepared before sampling");
        return sampling.texture();
    }

    @Override
    public void close() {
        if (!views.isEmpty()) BackendInternals.flushTextureReferences();
        for (var sampler : stencil.values()) sampler.close();
        stencil.clear();
        // Upstream shutdown releases one handle per name. Keep one name per physical allocation.
        if (!views.isEmpty()) BackendInternals.deduplicateTextures();
        ranges.clear();
        shared.clear();
        fresh.clear();
        immutable.clear();
        modes.clear();
        views.clear();
    }
}
