package dev.fopwoc.julia.backend.texture.buffer;

import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL31.*;
import static org.lwjgl.opengl.GL43.*;
import static org.lwjgl.sdl.SDLGPU.*;

import com.gtnewhorizons.angelica.sdlgpu.SDLGPUGate;
import com.gtnewhorizons.angelica.sdlgpu.frame.ContextState;
import com.gtnewhorizons.angelica.sdlgpu.shader.ShaderManager.ProgramObject;
import dev.fopwoc.julia.backend.Subsystems;
import dev.fopwoc.julia.backend.Upstream;
import dev.fopwoc.julia.backend.shader.LinkedPrograms;
import dev.fopwoc.julia.backend.texture.SamplerFallbacks;
import dev.fopwoc.julia.backend.texture.SamplerLayout;
import dev.fopwoc.julia.backend.texture.TextureBindings;
import dev.fopwoc.julia.backend.texture.TextureTable;
import dev.fopwoc.julia.backend.utils.Sdl;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.objects.ReferenceOpenHashSet;
import java.util.function.Supplier;
import org.lwjgl.sdl.SDL_GPUBufferCreateInfo;
import org.lwjgl.sdl.SDL_GPUBufferRegion;
import org.lwjgl.sdl.SDL_GPUTransferBufferCreateInfo;
import org.lwjgl.sdl.SDL_GPUTransferBufferLocation;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

/** GL buffer-texture attachments own views, not copies; replacement follows the buffer object's current store. */
public final class BufferTextures implements AutoCloseable {
    /** Reserved names below every GL name; one 16-byte texel of the sampler's scalar type. */
    private enum Placeholder {
        FLOAT(GL_SAMPLER_BUFFER, BufferTextureFormat.RGBA32F),
        INT(GL_INT_SAMPLER_BUFFER, BufferTextureFormat.RGBA32I),
        UNSIGNED(GL_UNSIGNED_INT_SAMPLER_BUFFER, BufferTextureFormat.RGBA32UI);
        final int type, name = -2 - ordinal();
        final BufferTextureFormat format;

        Placeholder(int type, BufferTextureFormat format) {
            this.type = type;
            this.format = format;
        }

        // values() clones its array, and these run per sampled texture on every draw.
        private static final Placeholder[] ALL = values();

        static Placeholder of(int type) {
            for (var placeholder : ALL) if (placeholder.type == type) return placeholder;
            throw new IllegalArgumentException("Not a buffer sampler type: 0x" + Integer.toHexString(type));
        }

        static Placeholder named(int name) {
            int index = -2 - name;
            return index >= 0 && index < ALL.length ? ALL[index] : null;
        }
    }

    private static final int PLACEHOLDER_BYTES = 16;

    private final Supplier<BufferTextureDriver> factory;
    private final TextureBindings bindings;
    private final TextureTable textures;
    private BufferTextureDriver driver;
    // Every live attachment; each name's own view sits on its texture record for per-draw lookups.
    private final ReferenceOpenHashSet<BufferTextureView> views = new ReferenceOpenHashSet<>();
    // Buffers sampled through a view since the last submission, so sub-data uploads need not scan every view.
    private final IntOpenHashSet readBuffers = new IntOpenHashSet();
    // Live views per retained SDL buffer handle, so buffer retirement need not scan every view.
    private final Long2IntOpenHashMap retained = new Long2IntOpenHashMap();
    private final LinkedPrograms programs;
    private final int[] bound = new int[TextureBindings.UNIT_COUNT];
    private final long[] placeholders = new long[Placeholder.values().length];
    private long placeholderBuffer;
    private int boundBuffer;
    private int alignment;
    private long epoch;

    public void beginFrame() {
        nextEpoch();
    }

    private void nextEpoch() {
        epoch++;
        readBuffers.clear();
    }

    public BufferTextures(
            Supplier<BufferTextureDriver> factory,
            TextureBindings bindings,
            TextureTable textures,
            LinkedPrograms programs) {
        this.factory = factory;
        this.bindings = bindings;
        this.textures = textures;
        this.programs = programs;
    }

    private BufferTextureView view(int name) {
        var texture = textures.get(name);
        return texture == null ? null : texture.buffer();
    }

    private BufferTextureDriver driver() {
        if (driver == null) driver = factory.get();
        return driver;
    }

    public int limit() {
        return driver().limit();
    }

    public int alignment() {
        if (alignment == 0)
            for (var format : BufferTextureFormat.SUPPORTED)
                alignment = Math.max(alignment, driver().alignment(format));
        return alignment;
    }

    public int boundBuffer() {
        return boundBuffer;
    }

    public void bindBuffer(int name) {
        boundBuffer = name;
    }

    public int boundTexture(int unit) {
        return bound[unit];
    }

    public void bindTexture(int unit, int name) {
        if (name < 0) throw new IllegalArgumentException("Negative buffer texture name");
        if (name != 0) {
            var texture = textures.getOrCreate(name);
            if (texture.target() != 0)
                throw new IllegalArgumentException("Texture target cannot change to GL_TEXTURE_BUFFER");
            if (texture.buffer() == null) {
                var view = new BufferTextureView();
                texture.buffer(view);
                views.add(view);
            }
            Subsystems.current().textureViews().bound(name);
        }
        if (bound[unit] != name) {
            bound[unit] = name;
            Upstream.context().samplerBindGen++;
        }
    }

    public void ordinaryTextureBound(int name) {
        if (name != 0 && view(name) != null)
            throw new IllegalArgumentException("Buffer texture rebound to an ordinary target");
    }

    public boolean contains(int name) {
        return view(name) != null || Placeholder.named(name) != null;
    }

    public void attach(int name, int format, int buffer, long offset, long size, boolean whole) {
        var old = name == 0 ? null : view(name);
        if (old == null) throw new IllegalArgumentException("No buffer texture is bound");
        var layout = BufferTextureFormat.get(format);
        if (buffer == 0) {
            var view = old;
            clear(view);
            view.format = layout;
            Upstream.context().samplerBindGen++;
            return;
        }
        var resources = Upstream.resources();
        long handle = resources.getBufferHandle(buffer);
        long actualSize = resources.getBufferSize(buffer);
        // glTexBuffer accepts an empty store; only an explicit range must be non-empty.
        if (handle == 0 && (!whole || actualSize != 0))
            throw new IllegalArgumentException("Buffer texture needs allocated GPU buffer storage");
        long length = whole ? actualSize : size;
        if (offset < 0
                || (!whole && length <= 0)
                || offset > actualSize
                || length > actualSize - offset
                || offset % alignment() != 0) throw new IllegalArgumentException("Invalid buffer texture range");
        var view = new BufferTextureView();
        view.buffer = buffer;
        view.format = layout;
        view.offset = offset;
        view.size = length;
        view.whole = whole;
        try {
            refresh(view);
        } catch (RuntimeException | Error failure) {
            clear(view);
            throw failure;
        }
        textures.get(name).buffer(view);
        views.remove(old);
        views.add(view);
        clear(old);
        Upstream.context().samplerBindGen++;
    }

    private void refresh(BufferTextureView view) {
        var resources = Upstream.resources();
        long handle = view.deleted ? view.bufferHandle : resources.getBufferHandle(view.buffer);
        long length = view.deleted ? view.size : view.whole ? resources.getBufferSize(view.buffer) : view.size;
        if (!view.deleted
                && (view.offset > resources.getBufferSize(view.buffer)
                        || length > resources.getBufferSize(view.buffer) - view.offset))
            throw new IllegalStateException("Buffer reallocation invalidated the attached texture range");
        int texels = Math.toIntExact(Math.min(length / view.format.bytes(), limit()));
        if (texels == 0) {
            // GL keeps a zero-width texture here; Metal has no empty texture views, so sampling uses a placeholder.
            if (view.texture != 0) {
                untrack(view);
                driver().release(view.texture);
                view.texture = 0;
                Upstream.context().samplerBindGen++;
            }
            view.identity = 0;
            view.bufferHandle = handle;
            view.size = length;
            view.texels = 0;
            return;
        }
        if (handle == 0) throw new IllegalStateException("Attached buffer storage disappeared");
        long identity = driver().identity(handle);
        if (identity == view.identity && handle == view.bufferHandle && length == view.size && view.texture != 0)
            return;
        long replacement = driver().create(handle, view.format, Math.toIntExact(view.offset), texels);
        untrack(view);
        driver().release(view.texture);
        view.texture = replacement;
        view.identity = identity;
        view.bufferHandle = handle;
        track(view);
        view.size = length;
        view.texels = texels;
        Upstream.context().samplerBindGen++;
    }

    private SamplerLayout layout(ProgramObject program) {
        return programs.of(program).samplers(program);
    }

    public int uniformType(ProgramObject program, String name, int fallback) {
        return layout(program).uniformType(name, fallback);
    }

    /** Unit-keyed binding for paths without sampler identity; an ordinary target wins over buffer samplers. */
    public int samplerBinding(ContextState state, int unit) {
        if (unit < 0 || unit >= bound.length)
            throw new IllegalArgumentException("Sampler texture unit out of range: " + unit);
        if (bindings.samplerOverride(state)) return state.boundTexture(unit);
        var program = state.boundProgramObj;
        if (program == null || !program.linked) return bindings.get(state, unit, GL_TEXTURE_2D);
        var layout = layout(program);
        if (!layout.bufferUnit(program, unit) || layout.hasOrdinaryTarget(program, unit))
            return bindings.get(state, unit, layout.ordinaryTarget(program, unit));
        return bufferBinding(unit, layout.bufferType(program, unit));
    }

    /** Binding for one backend sampler slot, read from its unit's binding for the slot's own target. */
    public int samplerBinding(ContextState state, int unit, int slot, boolean fragment) {
        var program = state.boundProgramObj;
        if (program == null || !program.linked || bindings.samplerOverride(state)) return samplerBinding(state, unit);
        var layout = layout(program);
        int target = layout.slotTarget(program, slot, fragment);
        if (target == 0) return samplerBinding(state, unit);
        if (target == GL_TEXTURE_BUFFER) return bufferBinding(unit, layout.slotBufferType(program, slot, fragment));
        return ordinaryBinding(state, unit, target, layout.slotScalar(program, slot, fragment));
    }

    public int computeSamplerBinding(ContextState state, int unit, String name) {
        var program = state.boundProgramObj;
        if (program == null || !program.linked || bindings.samplerOverride(state)) return samplerBinding(state, unit);
        var layout = layout(program);
        int target = layout.target(name);
        return target == GL_TEXTURE_BUFFER
                ? bufferBinding(unit, layout.bufferType(name))
                : ordinaryBinding(state, unit, target, layout.scalar(name));
    }

    private int ordinaryBinding(ContextState state, int unit, int target, int scalar) {
        int name = bindings.get(state, unit, target);
        return name == 0 ? SamplerFallbacks.name(target, scalar) : name;
    }

    // GL reads an unbound buffer texture without failing, so typed placeholders stand in for the missing view.
    private int bufferBinding(int unit, int type) {
        int name = bound[unit];
        return name != 0 ? name : Placeholder.of(type).name;
    }

    public long samplingHandle(int name) {
        var placeholder = Placeholder.named(name);
        if (placeholder != null) return placeholder(placeholder);
        var view = view(name);
        if (view == null) throw new IllegalArgumentException("Unknown buffer texture");
        if (view.buffer == 0) throw new IllegalStateException("Buffer texture has no attached storage");
        if (!view.deleted) {
            refresh(view);
            readBuffers.add(view.buffer);
        }
        view.readEpoch = epoch;
        return view.texels == 0 ? placeholder(Placeholder.of(view.format.samplerType())) : view.texture;
    }

    private long placeholder(Placeholder placeholder) {
        long texture = placeholders[placeholder.ordinal()];
        if (texture != 0) return texture;
        if (placeholderBuffer == 0)
            try (var stack = MemoryStack.stackPush()) {
                var info = SDL_GPUBufferCreateInfo.calloc(stack)
                        .usage(SDL_GPU_BUFFERUSAGE_GRAPHICS_STORAGE_READ)
                        .size(PLACEHOLDER_BYTES);
                placeholderBuffer = Sdl.require(SDL_CreateGPUBuffer(device(), info), "Create placeholder buffer");
                zero(stack, placeholderBuffer);
            }
        texture = driver().create(placeholderBuffer, placeholder.format, 0, 1);
        placeholders[placeholder.ordinal()] = texture;
        return texture;
    }

    // Placeholders may be created inside a render pass, so the upload gets its own command buffer, queued ahead of the
    // frame that samples it.
    private static void zero(MemoryStack stack, long buffer) {
        long transfer = Sdl.require(
                SDL_CreateGPUTransferBuffer(
                        device(),
                        SDL_GPUTransferBufferCreateInfo.calloc(stack)
                                .usage(SDL_GPU_TRANSFERBUFFERUSAGE_UPLOAD)
                                .size(PLACEHOLDER_BYTES)),
                "Create placeholder transfer buffer");
        try {
            var mapped = SDL_MapGPUTransferBuffer(device(), transfer, false, PLACEHOLDER_BYTES);
            if (mapped == null) throw Sdl.failure("Map placeholder transfer buffer");
            MemoryUtil.memSet(mapped, 0);
            SDL_UnmapGPUTransferBuffer(device(), transfer);
            long commands = Sdl.require(SDL_AcquireGPUCommandBuffer(device()), "Acquire placeholder upload commands");
            long copy = Sdl.require(SDL_BeginGPUCopyPass(commands), "Begin placeholder upload");
            SDL_UploadToGPUBuffer(
                    copy,
                    SDL_GPUTransferBufferLocation.calloc(stack).transfer_buffer(transfer),
                    SDL_GPUBufferRegion.calloc(stack).buffer(buffer).size(PLACEHOLDER_BYTES),
                    false);
            SDL_EndGPUCopyPass(copy);
            if (!SDL_SubmitGPUCommandBuffer(commands)) throw Sdl.failure("Submit placeholder upload");
        } finally {
            SDL_ReleaseGPUTransferBuffer(device(), transfer);
        }
    }

    private static long device() {
        return SDLGPUGate.device().getDevice();
    }

    public void prepare(ContextState state) {
        var program = state.boundProgramObj;
        if (program == null || !program.linked) return;
        var layout = layout(program);
        for (int unit = layout.nextBufferUnit(program, 0); unit >= 0; unit = layout.nextBufferUnit(program, unit + 1))
            if (bound[unit] != 0) samplingHandle(bound[unit]);
    }

    public int levelParameter(int name, int level, int parameter) {
        if (level != 0) throw new IllegalArgumentException("Buffer textures have only level zero");
        var view = view(name);
        if (view == null) throw new IllegalArgumentException("Unknown buffer texture");
        if (view.buffer != 0 && !view.deleted) refresh(view);
        return switch (parameter) {
            case GL_TEXTURE_WIDTH -> view.texels;
            case GL_TEXTURE_HEIGHT -> 1;
            case GL_TEXTURE_INTERNAL_FORMAT -> view.format == null ? GL_R8 : view.format.gl();
            case GL_TEXTURE_BUFFER_DATA_STORE_BINDING -> view.buffer;
            case GL_TEXTURE_BUFFER_OFFSET -> Math.toIntExact(view.offset);
            case GL_TEXTURE_BUFFER_SIZE -> Math.toIntExact(view.size);
            default -> throw new UnsupportedOperationException("Unsupported buffer texture query: " + parameter);
        };
    }

    public void beforeSubData(int name) {
        if (!readBuffers.contains(name)) return;
        Upstream.submitPendingCommands();
        nextEpoch();
    }

    public boolean retains(long handle) {
        return retained.containsKey(handle);
    }

    private void track(BufferTextureView view) {
        if (view.texture != 0) retained.addTo(view.bufferHandle, 1);
    }

    private void untrack(BufferTextureView view) {
        if (view.texture == 0) return;
        if (retained.addTo(view.bufferHandle, -1) == 1) retained.remove(view.bufferHandle);
    }

    public void deletedBuffer(int name) {
        if (boundBuffer == name) boundBuffer = 0;
        // A recycled name is a different buffer; views of the deleted one never invalidate its uploads.
        readBuffers.remove(name);
        for (var view : views) if (view.buffer == name) view.deleted = true;
    }

    public void deleteTexture(int name) {
        var texture = textures.get(name);
        var view = texture == null ? null : texture.buffer();
        if (view != null) {
            texture.buffer(null);
            views.remove(view);
            clear(view);
        }
        for (int unit = 0; unit < bound.length; unit++) if (bound[unit] == name) bound[unit] = 0;
    }

    private void clear(BufferTextureView view) {
        untrack(view);
        if (view.texture != 0) driver().release(view.texture);
        view.texture = view.bufferHandle = view.identity = view.offset = view.size = 0;
        view.buffer = view.texels = 0;
        view.format = null;
        view.deleted = false;
    }

    public void close() {
        for (int i = 0; i < placeholders.length; i++) {
            if (placeholders[i] != 0) driver().release(placeholders[i]);
            placeholders[i] = 0;
        }
        if (placeholderBuffer != 0) {
            SDL_ReleaseGPUBuffer(device(), placeholderBuffer);
            placeholderBuffer = 0;
        }
        for (var view : views) clear(view);
        views.clear();
        retained.clear();
        readBuffers.clear();
    }
}
