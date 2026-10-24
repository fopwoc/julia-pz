package dev.fopwoc.julia.backend.texture;

import static org.lwjgl.opengl.EXTTextureFilterAnisotropic.GL_TEXTURE_MAX_ANISOTROPY_EXT;
import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL12.*;
import static org.lwjgl.opengl.GL14.*;
import static org.lwjgl.opengl.GL30.GL_TEXTURE_2D_ARRAY;
import static org.lwjgl.opengl.GL43.GL_DEPTH_STENCIL_TEXTURE_MODE;
import static org.lwjgl.sdl.SDLGPU.*;

import com.gtnewhorizons.angelica.glsm.backend.BackendManager;
import com.gtnewhorizons.angelica.sdlgpu.SDLGPUGate;
import dev.fopwoc.julia.backend.Subsystems;
import dev.fopwoc.julia.backend.Upstream;
import dev.fopwoc.julia.backend.utils.Sdl;
import it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap;

/** Object identity and immutable allocation without modifying active units or texture bindings. */
public final class TextureObjects {
    // Bound on every texture bind, so names stay unboxed; GL texture targets are never 0.
    private final Int2IntOpenHashMap targets = new Int2IntOpenHashMap();

    public void bound(int name, int target) {
        if (name == 0) return;
        int previous = targets.putIfAbsent(name, target);
        if (previous != 0 && previous != target) throw new IllegalArgumentException("Texture target cannot change");
    }

    /** Whether {@code name} was already bound or created with {@code target}; the default name 0 always is. */
    public boolean known(int name, int target) {
        return name == 0 || targets.get(name) == target;
    }

    public boolean contains(int name) {
        return targets.containsKey(name);
    }

    public void deleted(int name) {
        targets.remove(name);
    }

    public int target(int name) {
        var meta = Upstream.resources().getTextureMeta(name);
        int target = targets.get(name);
        if (target == 0 && meta != null) target = meta.glTarget();
        if (name == 0 || target == 0) throw new IllegalArgumentException("Named texture does not exist: " + name);
        return target;
    }

    public int create(int target) {
        requireTarget(target);
        int name = BackendManager.RENDER_BACKEND.genTextures();
        bound(name, target);
        Subsystems.current().textureViews().bound(name);
        return name;
    }

    public void storage(int name, int levels, int format, int width, int height, int depth, boolean volume) {
        int target = target(name);
        if (volume ? target != GL_TEXTURE_3D && target != GL_TEXTURE_2D_ARRAY : target != GL_TEXTURE_2D)
            throw new IllegalArgumentException("Texture storage dimensionality does not match its target");
        int largest = Math.max(width, height);
        if (target == GL_TEXTURE_3D) largest = Math.max(largest, depth);
        if (width <= 0
                || height <= 0
                || depth <= 0
                || levels <= 0
                || levels > 32 - Integer.numberOfLeadingZeros(largest))
            throw new IllegalArgumentException("Invalid immutable texture dimensions or mip count");
        var resources = Upstream.resources();
        var views = Subsystems.current().textureViews();
        views.beforeAllocation(name);
        int sdlType = target == GL_TEXTURE_2D
                ? SDL_GPU_TEXTURETYPE_2D
                : target == GL_TEXTURE_3D ? SDL_GPU_TEXTURETYPE_3D : SDL_GPU_TEXTURETYPE_2D_ARRAY;
        int usage = SDL_GPU_TEXTUREUSAGE_SAMPLER;
        // Volume uploads need sampler storage; 2D attachments also need their matching target usage.
        if (TextureUploadFormat.depth(format)) usage |= SDL_GPU_TEXTUREUSAGE_DEPTH_STENCIL_TARGET;
        else if (target != GL_TEXTURE_3D) usage |= SDL_GPU_TEXTUREUSAGE_COLOR_TARGET;
        int requiredUsage = usage;
        int sdlFormat = TextureStorageFormat.select(
                format,
                candidate -> SDL_GPUTextureSupportsFormat(
                        SDLGPUGate.device().getDevice(), candidate, sdlType, requiredUsage));
        Upstream.clears().scrubPendingClearsForTexture(Upstream.context(), name);
        resources.releaseTextureHandleForRealloc(name);
        Sdl.require(
                resources.createTextureWithSdlFormat(
                        name, target, sdlFormat, format, width, height, depth, levels, usage),
                "Allocate named texture storage");
        resources.refreshTextureReferences(name);
        views.immutable(name);
    }

    public int levelParameter(int name, int level, int parameter) {
        if (level < 0) throw new IllegalArgumentException("Negative texture mip level");
        var buffers = Subsystems.current().bufferTextures();
        if (buffers.contains(name)) return buffers.levelParameter(name, level, parameter);
        int target = target(name);
        requireTarget(target);
        int limit = BackendManager.RENDER_BACKEND.getInteger(
                target == GL_TEXTURE_3D ? GL_MAX_3D_TEXTURE_SIZE : GL_MAX_TEXTURE_SIZE);
        if (limit <= 0) throw new IllegalStateException("Texture size limit is unavailable");
        if (level > 31 - Integer.numberOfLeadingZeros(limit))
            throw new IllegalArgumentException("Texture mip level exceeds the implementation limit");
        if (parameter != GL_TEXTURE_WIDTH
                && parameter != GL_TEXTURE_HEIGHT
                && parameter != GL_TEXTURE_DEPTH
                && parameter != GL_TEXTURE_INTERNAL_FORMAT)
            throw new UnsupportedOperationException("Named texture level parameter not implemented: " + parameter);
        var meta = Upstream.resources().getTextureMeta(name);
        if (meta == null || level >= meta.levels()) return parameter == GL_TEXTURE_INTERNAL_FORMAT ? GL_RGBA : 0;
        return switch (parameter) {
            case GL_TEXTURE_WIDTH -> Math.max(1, meta.width() >> Math.min(level, 30));
            case GL_TEXTURE_HEIGHT -> Math.max(1, meta.height() >> Math.min(level, 30));
            case GL_TEXTURE_DEPTH ->
                target == GL_TEXTURE_3D ? Math.max(1, meta.depth() >> Math.min(level, 30)) : meta.depth();
            default -> meta.glFormat();
        };
    }

    public void parameter(int name, int parameter, int value) {
        int target = target(name);
        if ((parameter == GL_TEXTURE_WRAP_S || parameter == GL_TEXTURE_WRAP_T || parameter == GL_TEXTURE_WRAP_R)
                && value != GL_REPEAT
                && value != GL_CLAMP_TO_EDGE
                && value != GL_MIRRORED_REPEAT)
            throw new UnsupportedOperationException("Named texture wrap mode not implemented: " + value);
        if (parameter == GL_TEXTURE_MAX_LEVEL && value < 0)
            throw new IllegalArgumentException("Negative texture maximum level");
        switch (parameter) {
            case GL_TEXTURE_BASE_LEVEL,
                    GL_TEXTURE_MIN_FILTER,
                    GL_TEXTURE_MAG_FILTER,
                    GL_TEXTURE_WRAP_S,
                    GL_TEXTURE_WRAP_T,
                    GL_TEXTURE_WRAP_R,
                    GL_TEXTURE_MAX_LEVEL,
                    GL_TEXTURE_COMPARE_MODE,
                    GL_TEXTURE_COMPARE_FUNC,
                    GL_DEPTH_STENCIL_TEXTURE_MODE,
                    GL_TEXTURE_MIN_LOD,
                    GL_TEXTURE_MAX_LOD,
                    GL_TEXTURE_MAX_ANISOTROPY_EXT -> {}
            default -> throw new UnsupportedOperationException("Named texture parameter not implemented: " + parameter);
        }
        BackendManager.RENDER_BACKEND.textureParameteri(name, target, parameter, value);
    }

    public void parameter(int name, int parameter, float value) {
        if (!Float.isFinite(value)) throw new IllegalArgumentException("Non-finite named texture parameter");
        if (parameter == GL_TEXTURE_MIN_LOD
                || parameter == GL_TEXTURE_MAX_LOD
                || parameter == GL_TEXTURE_LOD_BIAS
                || parameter == GL_TEXTURE_MAX_ANISOTROPY_EXT)
            BackendManager.RENDER_BACKEND.textureParameterf(name, target(name), parameter, value);
        else parameter(name, parameter, Math.round(value));
    }

    private static void requireTarget(int target) {
        if (target != GL_TEXTURE_2D && target != GL_TEXTURE_3D && target != GL_TEXTURE_2D_ARRAY)
            throw new UnsupportedOperationException("Named texture target not implemented: " + target);
    }
}
