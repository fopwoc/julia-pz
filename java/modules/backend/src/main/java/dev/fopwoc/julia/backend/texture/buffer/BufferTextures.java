package dev.fopwoc.julia.backend.texture.buffer;

import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL31.*;
import static org.lwjgl.opengl.GL43.*;

import com.gtnewhorizons.angelica.sdlgpu.frame.ContextState;
import com.gtnewhorizons.angelica.sdlgpu.shader.ShaderManager.ProgramObject;
import dev.fopwoc.julia.backend.BackendInternals;
import dev.fopwoc.julia.backend.texture.SamplerLayout;
import java.util.HashMap;
import java.util.HashSet;
import java.util.WeakHashMap;
import java.util.function.Supplier;

/** GL buffer-texture attachments own views, not copies; replacement follows the buffer object's current store. */
public final class BufferTextures implements AutoCloseable {
    private static final class View {
        int buffer;
        BufferTextureFormat format;
        long offset, size, bufferHandle, identity, texture;
        boolean whole, deleted;
        long readEpoch = -1;
        int texels;
    }

    private final Supplier<BufferTextureDriver> factory;
    private BufferTextureDriver driver;
    private final HashMap<Integer, View> views = new HashMap<>();
    private final HashSet<Integer> ordinaryTextures = new HashSet<>();
    private final WeakHashMap<ProgramObject, SamplerLayout> layouts = new WeakHashMap<>();
    private final int[] bound = new int[32];
    private int boundBuffer;
    private int alignment;
    private long epoch;

    public void beginFrame() {
        epoch++;
    }

    public BufferTextures(Supplier<BufferTextureDriver> factory) {
        this.factory = factory;
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
            if (ordinaryTextures.contains(name))
                throw new IllegalArgumentException("Texture target cannot change to GL_TEXTURE_BUFFER");
            views.computeIfAbsent(name, ignored -> new View());
            BackendInternals.views().bound(name);
        }
        if (bound[unit] != name) {
            bound[unit] = name;
            BackendInternals.context().samplerBindGen++;
        }
    }

    public void ordinaryTextureBound(int name) {
        if (name != 0) {
            if (views.containsKey(name))
                throw new IllegalArgumentException("Buffer texture rebound to an ordinary target");
            ordinaryTextures.add(name);
        }
    }

    public boolean contains(int name) {
        return views.containsKey(name);
    }

    public void attach(int name, int format, int buffer, long offset, long size, boolean whole) {
        if (name == 0 || !views.containsKey(name)) throw new IllegalArgumentException("No buffer texture is bound");
        var layout = BufferTextureFormat.get(format);
        if (buffer == 0) {
            var view = views.get(name);
            clear(view);
            view.format = layout;
            BackendInternals.context().samplerBindGen++;
            return;
        }
        var resources = BackendInternals.resources();
        long handle = resources.getBufferHandle(buffer);
        if (handle == 0) throw new IllegalArgumentException("Buffer texture needs allocated GPU buffer storage");
        long actualSize = resources.getBufferSize(buffer);
        long length = whole ? actualSize : size;
        if (offset < 0
                || length <= 0
                || offset > actualSize
                || length > actualSize - offset
                || offset % alignment() != 0) throw new IllegalArgumentException("Invalid buffer texture range");
        var old = views.get(name);
        var view = new View();
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
        views.put(name, view);
        clear(old);
        BackendInternals.context().samplerBindGen++;
    }

    private void refresh(View view) {
        var resources = BackendInternals.resources();
        long handle = view.deleted ? view.bufferHandle : resources.getBufferHandle(view.buffer);
        if (handle == 0) throw new IllegalStateException("Attached buffer storage disappeared");
        long identity = driver().identity(handle);
        long length = view.deleted ? view.size : view.whole ? resources.getBufferSize(view.buffer) : view.size;
        if (identity == view.identity && handle == view.bufferHandle && length == view.size && view.texture != 0)
            return;
        if (!view.deleted
                && (view.offset > resources.getBufferSize(view.buffer)
                        || length > resources.getBufferSize(view.buffer) - view.offset))
            throw new IllegalStateException("Buffer reallocation invalidated the attached texture range");
        int texels = Math.toIntExact(Math.min(length / view.format.bytes(), limit()));
        if (texels == 0) throw new IllegalArgumentException("Buffer texture range contains no complete texel");
        long replacement = driver().create(handle, view.format, Math.toIntExact(view.offset), texels);
        driver().release(view.texture);
        view.texture = replacement;
        view.identity = identity;
        view.bufferHandle = handle;
        view.size = length;
        view.texels = texels;
        BackendInternals.context().samplerBindGen++;
    }

    private SamplerLayout layout(ProgramObject program) {
        var layout = layouts.get(program);
        if (layout == null || !layout.matches(program)) {
            layout = new SamplerLayout(program);
            layouts.put(program, layout);
        }
        return layout;
    }

    public int uniformType(ProgramObject program, String name, int fallback) {
        return layout(program).uniformType(name, fallback);
    }

    public int samplerBinding(ContextState state, int unit) {
        if (unit < 0 || unit >= bound.length)
            throw new IllegalArgumentException("Sampler texture unit out of range: " + unit);
        if (BackendInternals.textureBindings().samplerOverride(state)) return state.boundTexture(unit);
        var program = state.boundProgramObj;
        if (program == null || !program.linked)
            return BackendInternals.textureBindings().get(state, unit, GL_TEXTURE_2D);
        if ((layout(program).bufferUnits(program) & 1 << unit) == 0)
            return BackendInternals.textureBindings()
                    .get(state, unit, layout(program).ordinaryTarget(program, unit));
        int name = bound[unit];
        if (name == 0) throw new IllegalStateException("No buffer texture bound to sampler unit " + unit);
        return name;
    }

    public long samplingHandle(int name) {
        var view = views.get(name);
        if (view == null) throw new IllegalArgumentException("Unknown buffer texture");
        if (view.buffer == 0) throw new IllegalStateException("Buffer texture has no attached storage");
        if (!view.deleted) refresh(view);
        view.readEpoch = epoch;
        return view.texture;
    }

    public void prepare(ContextState state) {
        var program = state.boundProgramObj;
        if (program == null || !program.linked) return;
        int mask = layout(program).bufferUnits(program);
        while (mask != 0) {
            int unit = Integer.numberOfTrailingZeros(mask);
            samplingHandle(samplerBinding(state, unit));
            mask &= mask - 1;
        }
    }

    public int levelParameter(int name, int level, int parameter) {
        if (level != 0) throw new IllegalArgumentException("Buffer textures have only level zero");
        var view = views.get(name);
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
        for (var view : views.values()) {
            if (view.buffer != name || view.deleted || view.readEpoch != epoch) continue;
            BackendInternals.submitPendingCommands();
            epoch++;
            return;
        }
    }

    public boolean retains(long handle) {
        for (var view : views.values()) if (view.texture != 0 && view.bufferHandle == handle) return true;
        return false;
    }

    public void deletedBuffer(int name) {
        if (boundBuffer == name) boundBuffer = 0;
        for (var view : views.values()) if (view.buffer == name) view.deleted = true;
    }

    public void deleteTexture(int name) {
        ordinaryTextures.remove(name);
        var view = views.remove(name);
        if (view != null) clear(view);
        for (int unit = 0; unit < bound.length; unit++) if (bound[unit] == name) bound[unit] = 0;
    }

    private void clear(View view) {
        if (view.texture != 0) driver().release(view.texture);
        view.texture = view.bufferHandle = view.identity = view.offset = view.size = 0;
        view.buffer = view.texels = 0;
        view.format = null;
        view.deleted = false;
    }

    public void close() {
        for (var view : views.values()) clear(view);
        views.clear();
        layouts.clear();
        ordinaryTextures.clear();
    }
}
