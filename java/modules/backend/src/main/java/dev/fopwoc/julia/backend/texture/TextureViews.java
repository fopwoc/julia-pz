package dev.fopwoc.julia.backend.texture;

import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL30.*;
import static org.lwjgl.opengl.GL43.*;
import static org.lwjgl.opengl.GL44.*;

import com.gtnewhorizons.angelica.sdlgpu.frame.ContextState;
import com.gtnewhorizons.angelica.sdlgpu.resource.ResourceManager;
import com.gtnewhorizons.angelica.sdlgpu.sampler.SamplerLookup;
import dev.fopwoc.julia.backend.BackendInternals;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** Full-image, same-format 2D aliases. Each name has independent texture state and shares physical storage. */
public final class TextureViews implements AutoCloseable {
    private final Set<Integer> fresh = new HashSet<>();
    private final Set<Integer> immutable = new HashSet<>();
    private final Map<Integer, Integer> modes = new HashMap<>();
    private final Set<Integer> views = new HashSet<>();
    private final Map<Long, StencilSampling> stencil = new HashMap<>();

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
        validate(meta, target, format, minLevel, levels, minLayer, layers);
        BackendInternals.aliasTexture(name, original);
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
        if (target != GL_TEXTURE_2D
                || meta.glTarget() != GL_TEXTURE_2D
                || meta.depth() != 1
                || format != meta.glFormat()
                || minLevel != 0
                || levels != meta.levels()
                || minLayer != 0
                || layers != 1) {
            throw new UnsupportedOperationException("Only full-image, same-format 2D texture views are supported");
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
        return switch (parameter) {
            case GL_DEPTH_STENCIL_TEXTURE_MODE -> modes.getOrDefault(name, GL_DEPTH_COMPONENT);
            case GL_TEXTURE_IMMUTABLE_FORMAT -> immutable.contains(name) ? GL_TRUE : GL_FALSE;
            case GL_TEXTURE_IMMUTABLE_LEVELS ->
                immutable.contains(name)
                        ? BackendInternals.resources().getTextureMeta(name).levels()
                        : 0;
            case GL_TEXTURE_VIEW_MIN_LEVEL, GL_TEXTURE_VIEW_MIN_LAYER -> 0;
            case GL_TEXTURE_VIEW_NUM_LEVELS ->
                immutable.contains(name)
                        ? BackendInternals.resources().getTextureMeta(name).levels()
                        : 0;
            case GL_TEXTURE_VIEW_NUM_LAYERS -> immutable.contains(name) ? 1 : 0;
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
        }
        fresh.remove(name);
        immutable.remove(name);
        views.remove(name);
        modes.remove(name);
    }

    public void beforeCompute() {
        if (!modes.containsValue(GL_STENCIL_INDEX)) return;
        for (int name : BackendInternals.context().boundTextures) {
            if (modes.getOrDefault(name, GL_DEPTH_COMPONENT) == GL_STENCIL_INDEX) {
                throw new UnsupportedOperationException(
                        "Compute dispatch with a bound stencil-aspect texture is unsupported");
            }
        }
    }

    public void prepare(ContextState state) {
        if (!modes.containsValue(GL_STENCIL_INDEX)) return;
        var program = BackendInternals.shaders().getProgram(state.boundProgram);
        if (program == null || !program.linked) return;
        var done = new HashSet<Long>();
        for (boolean fragment : new boolean[] {false, true}) {
            for (int unit : SamplerLookup.resolvedUnits(program, fragment)) {
                int name = BackendInternals.bufferTextures().samplerBinding(state, unit);
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
        fresh.clear();
        immutable.clear();
        modes.clear();
        views.clear();
    }
}
