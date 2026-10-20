package dev.fopwoc.julia.backend;

import static org.lwjgl.opengl.GL11.GL_INVALID_OPERATION;
import static org.lwjgl.opengl.GL11.GL_RED;
import static org.lwjgl.opengl.GL11.GL_RGBA;
import static org.lwjgl.opengl.GL11.GL_RGBA8;
import static org.lwjgl.opengl.GL11.GL_TEXTURE_2D;
import static org.lwjgl.opengl.GL11.GL_UNSIGNED_BYTE;
import static org.lwjgl.opengl.GL12.*;
import static org.lwjgl.opengl.GL15.GL_ELEMENT_ARRAY_BUFFER;
import static org.lwjgl.opengl.GL20.GL_ACTIVE_UNIFORMS;
import static org.lwjgl.opengl.GL20.GL_ACTIVE_UNIFORM_MAX_LENGTH;
import static org.lwjgl.opengl.GL20.GL_ATTACHED_SHADERS;
import static org.lwjgl.opengl.GL20.GL_LINK_STATUS;
import static org.lwjgl.opengl.GL20.GL_MAX_COMBINED_TEXTURE_IMAGE_UNITS;
import static org.lwjgl.opengl.GL20.GL_MAX_TEXTURE_IMAGE_UNITS;
import static org.lwjgl.opengl.GL20.GL_MAX_VERTEX_TEXTURE_IMAGE_UNITS;
import static org.lwjgl.opengl.GL30.*;
import static org.lwjgl.opengl.GL30.GL_R8;
import static org.lwjgl.opengl.GL31.GL_COPY_READ_BUFFER;
import static org.lwjgl.opengl.GL31.GL_COPY_WRITE_BUFFER;
import static org.lwjgl.opengl.GL31.GL_MAX_TEXTURE_BUFFER_SIZE;
import static org.lwjgl.opengl.GL31.GL_TEXTURE_BINDING_BUFFER;
import static org.lwjgl.opengl.GL31.GL_TEXTURE_BUFFER;
import static org.lwjgl.opengl.GL40.GL_DRAW_INDIRECT_BUFFER_BINDING;
import static org.lwjgl.opengl.GL41.GL_NUM_PROGRAM_BINARY_FORMATS;
import static org.lwjgl.opengl.GL43.GL_DEPTH_STENCIL_TEXTURE_MODE;
import static org.lwjgl.opengl.GL43.GL_MAX_VERTEX_ATTRIB_BINDINGS;
import static org.lwjgl.opengl.GL43.GL_TEXTURE_BUFFER_OFFSET_ALIGNMENT;
import static org.lwjgl.opengl.GL43.GL_VERTEX_BINDING_DIVISOR;

import com.gtnewhorizons.angelica.glsm.backend.BackendOptions;
import com.gtnewhorizons.angelica.sdlgpu.SDLGPUGate;
import com.gtnewhorizons.angelica.sdlgpu.SDLGPURenderBackend;
import com.gtnewhorizons.angelica.sdlgpu.frame.ContextState;
import com.gtnewhorizons.angelica.sdlgpu.util.DebugMessageRelay;
import dev.fopwoc.julia.backend.buffer.IndexBufferUploads;
import dev.fopwoc.julia.backend.framebuffer.Framebuffers;
import dev.fopwoc.julia.backend.platform.Platform;
import dev.fopwoc.julia.backend.query.Queries;
import dev.fopwoc.julia.backend.shader.MatrixUniformUploads;
import dev.fopwoc.julia.backend.shader.ProgramLinkState;
import dev.fopwoc.julia.backend.shader.ProgramResourceCache;
import dev.fopwoc.julia.backend.shader.SamplerResources;
import dev.fopwoc.julia.backend.shader.SamplerUniforms;
import dev.fopwoc.julia.backend.shader.ShaderPrograms;
import dev.fopwoc.julia.backend.sync.GpuSyncs;
import dev.fopwoc.julia.backend.texture.ClientPixels;
import dev.fopwoc.julia.backend.texture.NamedTextureUploads;
import dev.fopwoc.julia.backend.texture.TextureBindings;
import dev.fopwoc.julia.backend.texture.TextureObjects;
import dev.fopwoc.julia.backend.texture.TextureViews;
import dev.fopwoc.julia.backend.texture.VolumeUnpack;
import dev.fopwoc.julia.backend.texture.buffer.BufferTextures;
import dev.fopwoc.julia.backend.vertex.BaseInstanceDraws;
import dev.fopwoc.julia.backend.vertex.VertexInputs;
import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.util.HashSet;
import java.util.WeakHashMap;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

/** Selects the real SDL backend for an explicitly created device without Minecraft's display gate. */
public final class HostedBackend extends SDLGPURenderBackend {
    private static final System.Logger LOG = System.getLogger(HostedBackend.class.getName());
    private int error;
    private final GpuSyncs syncs = new GpuSyncs(this);

    GpuSyncs syncs() {
        return syncs;
    }

    @Override
    public void drawArraysInstanced(int mode, int first, int count, int instances) {
        if (count <= 0 || instances <= 0) return;
        try (var _ = BaseInstanceDraws.offsets(BackendInternals.context())) {
            super.drawArraysInstanced(mode, first, count, instances);
        }
    }

    @Override
    public void flush() {
        BackendInternals.timestampCommandBuffer();
        BackendInternals.submitPendingCommands();
    }

    @Override
    public void finish() {
        flush();
        super.finish();
    }

    private final ShaderPrograms shaderPrograms = new ShaderPrograms();
    private final Framebuffers framebuffers = new Framebuffers();

    public Framebuffers framebuffers() {
        return framebuffers;
    }

    @Override
    public void bindFramebuffer(int target, int name) {
        super.bindFramebuffer(target, name);
        if (target != GL_READ_FRAMEBUFFER) framebuffers.bound(BackendInternals.context().boundFboId);
    }

    @Override
    public void framebufferTexture2D(int target, int attachment, int textureTarget, int texture, int level) {
        if (texture != 0 && textureTarget != GL_TEXTURE_2D)
            throw new UnsupportedOperationException(
                    "FramebufferTexture2D texture target not implemented: " + textureTarget);
        framebuffers.attach(framebuffers.selected(target), attachment, texture, level, 0, false);
    }

    @Override
    public void framebufferTexture(int target, int attachment, int texture, int level) {
        framebuffers.attach(framebuffers.selected(target), attachment, texture, level, 0, false);
    }

    @Override
    public int checkFramebufferStatus(int target) {
        return framebuffers.status(framebuffers.selected(target), target);
    }

    @Override
    public void namedFramebufferTexture(int name, int attachment, int texture, int level) {
        framebuffers.attach(name, attachment, texture, level, 0, false);
    }

    @Override
    public void namedFramebufferReadBuffer(int name, int buffer) {
        framebuffers.readBuffer(name, buffer);
    }

    @Override
    public void namedFramebufferDrawBuffers(int name, IntBuffer buffers) {
        int[] values = new int[buffers.remaining()];
        for (int i = 0; i < values.length; i++) values[i] = buffers.get(buffers.position() + i);
        framebuffers.drawBuffers(name, values);
    }

    @Override
    public void drawBuffer(int buffer) {
        drawBuffers(buffer);
    }

    @Override
    public void drawBuffers(int buffer) {
        var state = BackendInternals.context();
        if (state.boundFboId == 0 || state.boundFboId == state.defaultFboId) {
            super.drawBuffers(buffer);
            return;
        }
        framebuffers.drawBuffers(state.boundFboId, new int[] {buffer});
    }

    @Override
    public void drawBuffers(IntBuffer buffers) {
        var state = BackendInternals.context();
        if (state.boundFboId == 0 || state.boundFboId == state.defaultFboId) {
            super.drawBuffers(buffers);
            return;
        }
        namedFramebufferDrawBuffers(state.boundFboId, buffers);
    }

    @Override
    public void readBuffer(int buffer) {
        var state = BackendInternals.context();
        if (state.boundReadFboId == 0 || state.boundReadFboId == state.defaultFboId) {
            super.readBuffer(buffer);
            return;
        }
        framebuffers.readBuffer(state.boundReadFboId, buffer);
    }

    @Override
    public void clear(int mask) {
        textureViews.flushSharedClears();
        var state = BackendInternals.context();
        framebuffers.validateDraw(state);
        var fbo = BackendInternals.resources().getFbo(state.boundFboId);
        if (fbo != null && framebuffers.sliced(fbo)) {
            BackendInternals.clearFramebufferSlice(mask);
            return;
        }
        super.clear(mask);
    }

    @Override
    public void readPixels(int x, int y, int w, int h, int format, int type, ByteBuffer pixels) {
        framebuffers.validateRead(format);
        super.readPixels(x, y, w, h, format, type, pixels);
    }

    @Override
    public void readPixels(int x, int y, int w, int h, int format, int type, FloatBuffer pixels) {
        framebuffers.validateRead(format);
        super.readPixels(x, y, w, h, format, type, pixels);
    }

    @Override
    public void readPixels(int x, int y, int w, int h, int format, int type, IntBuffer pixels) {
        framebuffers.validateRead(format);
        super.readPixels(x, y, w, h, format, type, pixels == null ? null : MemoryUtil.memByteBuffer(pixels));
    }

    @Override
    public void readPixels(int x, int y, int w, int h, int format, int type, long pixels) {
        framebuffers.validateRead(format);
        super.readPixels(x, y, w, h, format, type, pixels);
    }

    @Override
    public void copyTexSubImage2D(int target, int level, int x, int y, int sx, int sy, int width, int height) {
        framebuffers.requireFlatRead(BackendInternals.context().boundReadFboId);
        super.copyTexSubImage2D(target, level, x, y, sx, sy, width, height);
    }

    @Override
    public void copyTexImage2D(int target, int level, int format, int x, int y, int width, int height, int border) {
        framebuffers.requireFlatRead(BackendInternals.context().boundReadFboId);
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
        framebuffers.requireFlatRead(read);
        framebuffers.requireFlatRead(draw);
        super.blitNamedFramebuffer(read, draw, sx0, sy0, sx1, sy1, dx0, dy0, dx1, dy1, mask, filter);
    }

    private final TextureBindings textureBindings = new TextureBindings();

    public TextureBindings textureBindings() {
        return textureBindings;
    }

    private final TextureObjects textureObjects = new TextureObjects();
    private final WeakHashMap<ContextState, VolumeUnpack> volumeUnpack = new WeakHashMap<>();

    private final TextureViews textureViews = new TextureViews();
    private final BufferTextures bufferTextures =
            new BufferTextures(() -> Platform.current().createBufferTextureDriver());

    public TextureObjects textureObjects() {
        return textureObjects;
    }

    public VolumeUnpack volumeUnpack() {
        return volumeUnpack.computeIfAbsent(BackendInternals.context(), ignored -> new VolumeUnpack());
    }

    @Override
    public int createTextures(int target) {
        return textureObjects.create(target);
    }

    @Override
    public void pixelStorei(int parameter, int value) {
        if (parameter == GL_UNPACK_IMAGE_HEIGHT || parameter == GL_UNPACK_SKIP_IMAGES) {
            if (value < 0) throw new IllegalArgumentException("Negative pixel unpack value");
            if (parameter == GL_UNPACK_IMAGE_HEIGHT) volumeUnpack().imageHeight = value;
            else volumeUnpack().skipImages = value;
        } else if (parameter == GL_UNPACK_SWAP_BYTES) volumeUnpack().swapBytes = value != 0;
        else super.pixelStorei(parameter, value);
    }

    public BufferTextures bufferTextures() {
        return bufferTextures;
    }

    @Override
    public int getInteger(int parameter) {
        int target = TextureBindings.queryTarget(parameter);
        if (target != 0) {
            var state = BackendInternals.context();
            return textureBindings.get(state, state.activeTextureUnit, target);
        }
        return switch (parameter) {
            // Julia does not expose OpenGL program binaries; its internal shader cache is separate.
            case GL_NUM_PROGRAM_BINARY_FORMATS -> 0;
            case GL_MAX_TEXTURE_IMAGE_UNITS, GL_MAX_VERTEX_TEXTURE_IMAGE_UNITS -> SamplerResources.LIMIT;
            case GL_MAX_COMBINED_TEXTURE_IMAGE_UNITS -> TextureBindings.UNIT_COUNT;
            case GL_COPY_READ_BUFFER -> BackendInternals.context().boundCopyReadBuffer;
            case GL_COPY_WRITE_BUFFER -> BackendInternals.context().boundCopyWriteBuffer;
            case GL_DRAW_INDIRECT_BUFFER_BINDING -> BackendInternals.context().boundIndirectBuffer;
            case GL_UNPACK_IMAGE_HEIGHT -> volumeUnpack().imageHeight;
            case GL_UNPACK_SKIP_IMAGES -> volumeUnpack().skipImages;
            case GL_UNPACK_SWAP_BYTES -> volumeUnpack().swapBytes ? GL_TRUE : GL_FALSE;
            case GL_MAX_VERTEX_ATTRIB_BINDINGS -> ContextState.MAX_VERTEX_ATTRIBS;
            case GL_MAX_TEXTURE_BUFFER_SIZE -> bufferTextures.limit();
            case GL_TEXTURE_BUFFER_OFFSET_ALIGNMENT -> bufferTextures.alignment();
            case GL_TEXTURE_BINDING_BUFFER -> bufferTextures.boundTexture(BackendInternals.context().activeTextureUnit);
            case GL_TEXTURE_BUFFER -> bufferTextures.boundBuffer();
            default -> super.getInteger(parameter);
        };
    }

    @Override
    public int getIntegerIndexed(int parameter, int index) {
        return parameter == GL_VERTEX_BINDING_DIVISOR
                ? VertexInputs.divisor(index)
                : super.getIntegerIndexed(parameter, index);
    }

    @Override
    public void vertexAttribFormat(int attribute, int size, int type, boolean normalized, int offset) {
        VertexInputs.modernAttribute(attribute);
        super.vertexAttribFormat(attribute, size, type, normalized, offset);
    }

    @Override
    public void vertexAttribIFormat(int attribute, int size, int type, int offset) {
        VertexInputs.modernAttribute(attribute);
        super.vertexAttribIFormat(attribute, size, type, offset);
    }

    @Override
    public void vertexAttribBinding(int attribute, int binding) {
        VertexInputs.validateBinding(binding);
        VertexInputs.modernAttribute(attribute);
        super.vertexAttribBinding(attribute, binding);
    }

    @Override
    public void bindVertexBuffer(int binding, int buffer, long offset, int stride) {
        VertexInputs.modernBinding(binding);
        super.bindVertexBuffer(binding, buffer, offset, stride);
    }

    @Override
    public void vertexAttribPointer(int attribute, int size, int type, boolean normalized, int stride, long offset) {
        VertexInputs.legacyPointer(attribute);
        super.vertexAttribPointer(attribute, size, type, normalized, stride, offset);
    }

    @Override
    public void vertexAttribIPointer(int attribute, int size, int type, int stride, long offset) {
        VertexInputs.legacyPointer(attribute);
        super.vertexAttribIPointer(attribute, size, type, stride, offset);
    }

    @Override
    public void vertexAttribDivisor(int attribute, int divisor) {
        VertexInputs.attributeDivisor(attribute, divisor);
    }

    @Override
    public void bindBuffer(int target, int name) {
        if (target == GL_TEXTURE_BUFFER) bufferTextures.bindBuffer(name);
        else super.bindBuffer(target, name);
        if (name != 0 && BackendInternals.context().getBoundBuffer(target) == name) bufferObjects.add(name);
    }

    @Override
    public void bufferSubData(int target, long offset, ByteBuffer data) {
        endComputeDispatchBatch();
        bufferTextures.beforeSubData(BackendInternals.context().getBoundBuffer(target));
        super.bufferSubData(target, offset, data);
    }

    private final HashSet<Integer> bufferObjects = new HashSet<>();

    @Override
    public int createBuffers() {
        int name = super.createBuffers();
        bufferObjects.add(name);
        return name;
    }

    @Override
    public boolean isBuffer(int name) {
        return bufferObjects.contains(name) || super.isBuffer(name);
    }

    @Override
    public void deleteBuffers(int name) {
        bufferObjects.remove(name);
        bufferTextures.deletedBuffer(name);
        super.deleteBuffers(name);
    }

    @Override
    public void deleteBuffers(IntBuffer names) {
        for (int i = names.position(); i < names.limit(); i++) deleteBuffers(names.get(i));
    }

    @Override
    public int getTexLevelParameteri(int target, int level, int parameter) {
        if (target == GL_TEXTURE_BUFFER)
            return bufferTextures.levelParameter(
                    bufferTextures.boundTexture(BackendInternals.context().activeTextureUnit), level, parameter);
        if (parameter == GL_TEXTURE_DEPTH && TextureBindings.selectable(target)) {
            var state = BackendInternals.context();
            var meta = BackendInternals.resources()
                    .getTextureMeta(textureBindings.get(state, state.activeTextureUnit, target));
            if (meta == null || level < 0 || level >= meta.levels()) return 0;
            return target == GL_TEXTURE_3D ? Math.max(1, meta.depth() >> Math.min(level, 30)) : meta.depth();
        }
        return super.getTexLevelParameteri(target, level, parameter);
    }

    private final ProgramResourceCache programResources = new ProgramResourceCache();

    private final Queries queries =
            new Queries(Platform.current()::createTimestampDriver, Platform.current()::createOcclusionDriver);

    @Override
    public int genQueries() {
        return queries.generate();
    }

    @Override
    public void genQueries(IntBuffer names) {
        for (int i = names.position(); i < names.limit(); i++) names.put(i, genQueries());
    }

    @Override
    public void deleteQueries(int name) {
        queries.delete(name);
    }

    @Override
    public boolean isQuery(int name) {
        return queries.isQuery(name);
    }

    @Override
    public void queryCounter(int name, int target) {
        queries.timestamp(name, target);
    }

    @Override
    public void beginQuery(int target, int name) {
        queries.begin(target, name);
    }

    @Override
    public void endQuery(int target) {
        queries.end(target);
    }

    @Override
    public int getQueryObjecti(int name, int parameter) {
        return (int) queries.get(name, parameter);
    }

    @Override
    public long getQueryObjectui64(int name, int parameter) {
        return queries.get(name, parameter);
    }

    @Override
    public void getQueryObjectui(int name, int parameter, IntBuffer result) {
        result.put(result.position(), getQueryObjecti(name, parameter));
    }

    ShaderPrograms shaderPrograms() {
        return shaderPrograms;
    }

    public ProgramResourceCache programResources() {
        return programResources;
    }

    public TextureViews textureViews() {
        return textureViews;
    }

    public void renamedTexture(int shader, boolean renamed) {
        shaderPrograms.renamedTexture(shader, renamed);
    }

    @Override
    public int getUniformLocation(int program, CharSequence name) {
        String backendName = shaderPrograms.backendUniformName(program, name.toString());
        var object = BackendInternals.shaders().getProgram(program);
        if (object != null && object.allSamplerNames.contains(backendName + "[0]")) backendName += "[0]";
        return super.getUniformLocation(program, backendName);
    }

    @Override
    public int getUniformLocation(int program, ByteBuffer name) {
        return getUniformLocation(program, MemoryUtil.memASCII(name));
    }

    @Override
    public String getActiveUniform(int program, int index, int capacity, IntBuffer sizeType) {
        String name = publicUniformName(program, index, sizeType);
        return capacity > 0 && name.length() > capacity ? name.substring(0, capacity) : name;
    }

    private String publicUniformName(int program, int index, IntBuffer sizeType) {
        String name = super.getActiveUniform(program, index, 0, sizeType);
        if (sizeType != null && sizeType.remaining() >= 2) {
            int at = sizeType.position() + 1;
            sizeType.put(
                    at,
                    bufferTextures.uniformType(BackendInternals.shaders().getProgram(program), name, sizeType.get(at)));
        }
        return shaderPrograms.publicUniformName(program, name);
    }

    @Override
    public void getActiveUniform(
            int program, int index, IntBuffer length, IntBuffer size, IntBuffer type, ByteBuffer name) {
        try (var stack = MemoryStack.stackPush()) {
            var sizeType = stack.mallocInt(2);
            String publicName = publicUniformName(program, index, sizeType);
            if (size != null && size.hasRemaining()) size.put(size.position(), sizeType.get(0));
            if (type != null && type.hasRemaining()) type.put(type.position(), sizeType.get(1));
            ShaderPrograms.write(publicName, length, name);
        }
    }

    public String preprocessShaderSource(int type, String source) {
        return shaderPrograms.preprocess(type, source);
    }

    @Override
    public int createShader(int type) {
        int id = super.createShader(type);
        shaderPrograms.created(id, type);
        return id;
    }

    @Override
    public int createProgram() {
        int id = super.createProgram();
        shaderPrograms.programCreated(id);
        return id;
    }

    @Override
    public void shaderSource(int shader, CharSequence source) {
        shaderPrograms.source(shader, source);
    }

    @Override
    public void compileShader(int shader) {
        shaderPrograms.compile(shader);
    }

    @Override
    public void deleteShader(int shader) {
        shaderPrograms.delete(shader);
    }

    @Override
    public void attachShader(int program, int shader) {
        shaderPrograms.attach(program, shader);
    }

    @Override
    public void detachShader(int program, int shader) {
        shaderPrograms.detach(program, shader);
    }

    @Override
    public void linkProgram(int program) {
        if (shaderPrograms.link(program)) {
            ProgramLinkState.reset(BackendInternals.shaders().getProgram(program));
            super.linkProgram(program);
            if (super.getProgrami(program, GL_LINK_STATUS) == 0)
                throw new UnsupportedOperationException("SDL shader program translation failed for program " + program
                        + ": " + super.getProgramInfoLog(program));
            ProgramLinkState.linked(BackendInternals.shaders().getProgram(program), BackendInternals.context());
            shaderPrograms.initializeUniforms(program);
        }
    }

    @Override
    public void uniform1iv(int location, IntBuffer values) {
        if (!SamplerUniforms.upload(location, values)) super.uniform1iv(location, values);
    }

    @Override
    public void deleteProgram(int program) {
        super.deleteProgram(program);
        shaderPrograms.programDeleted(program);
    }

    @Override
    public void useProgram(int program) {
        super.useProgram(program);
        shaderPrograms.releaseDeletedPrograms();
    }

    @Override
    public int getShaderi(int shader, int parameter) {
        return shaderPrograms.contains(shader)
                ? shaderPrograms.parameter(shader, parameter)
                : super.getShaderi(shader, parameter);
    }

    @Override
    public int getProgrami(int program, int parameter) {
        if (parameter == GL_ACTIVE_UNIFORM_MAX_LENGTH && shaderPrograms.renamedTexture(program)) {
            int longest = 0;
            for (int i = 0; i < super.getProgrami(program, GL_ACTIVE_UNIFORMS); i++)
                longest = Math.max(longest, publicUniformName(program, i, null).length() + 1);
            return longest;
        }
        return parameter == GL_ATTACHED_SHADERS
                ? shaderPrograms.attached(program)
                : super.getProgrami(program, parameter);
    }

    @Override
    public void getAttachedShaders(int program, IntBuffer count, IntBuffer shaders) {
        shaderPrograms.attached(program, count, shaders);
    }

    @Override
    public String getShaderInfoLog(int shader, int maxLength) {
        return ShaderPrograms.truncate(shaderPrograms.log(shader), maxLength);
    }

    @Override
    public void getShaderInfoLog(int shader, IntBuffer length, ByteBuffer output) {
        ShaderPrograms.write(shaderPrograms.log(shader), length, output);
    }

    @Override
    public String getShaderSource(int shader, int maxLength) {
        return ShaderPrograms.truncate(shaderPrograms.source(shader), maxLength);
    }

    @Override
    public void getShaderSource(int shader, IntBuffer length, ByteBuffer output) {
        ShaderPrograms.write(shaderPrograms.source(shader), length, output);
    }

    @Override
    public void bufferData(int target, ByteBuffer data, int usage) {
        int name =
                target == GL_ELEMENT_ARRAY_BUFFER ? BackendInternals.context().getBoundBuffer(target) : 0;
        boolean remember = name != 0 && data != null && data.hasRemaining() && !isBufferImmutable(name);
        super.bufferData(target, data, usage);
        if (remember) IndexBufferUploads.remember(name, data);
    }

    @Override
    public void viewport(int x, int y, int width, int height) {
        super.viewport(x, y, width, height);
        // SDL still needs a clipping rectangle when GL scissor testing is disabled.
        // Upstream derives it from the viewport, so both native states must change together.
        var state = BackendInternals.context();
        if (!state.scissorEnabled) state.scissorDirty = true;
    }

    @Override
    public void drawElementsInstanced(int mode, int count, int type, long indices, int instances) {
        if (count <= 0 || instances <= 0) return;
        try (var _ = BaseInstanceDraws.offsets(BackendInternals.context())) {
            super.drawElementsInstanced(mode, count, type, indices, instances);
        }
    }

    @Override
    public int genTextures() {
        int name = super.genTextures();
        textureViews.generated(name);
        return name;
    }

    @Override
    public void genTextures(IntBuffer names) {
        for (int i = names.position(); i < names.limit(); i++) names.put(i, genTextures());
    }

    @Override
    public void bindImageTexture(int unit, int texture, int level, boolean layered, int layer, int access, int format) {
        if (bufferTextures.contains(texture))
            throw new UnsupportedOperationException("Buffer texture image load/store is not implemented");
        super.bindImageTexture(unit, texture, level, layered, layer, access, format);
    }

    @Override
    public boolean isTexture(int name) {
        return textureObjects.contains(name) || bufferTextures.contains(name) || super.isTexture(name);
    }

    @Override
    public void bindTextureUnit(int unit, int name) {
        var state = BackendInternals.context();
        if (name == 0) {
            textureBindings.unbindUnit(state, unit);
            bufferTextures.bindTexture(unit, 0);
        } else if (bufferTextures.contains(name)) bufferTextures.bindTexture(unit, name);
        else textureBindings.bind(state, unit, textureObjects.target(name), name);
        super.bindTextureUnit(unit, name);
    }

    @Override
    public void bindTexture(int target, int name) {
        textureObjects.bound(name, target);
        var state = BackendInternals.context();
        textureBindings.bind(state, state.activeTextureUnit, target, name);
        textureViews.bound(name);
        bufferTextures.ordinaryTextureBound(name);
        super.bindTexture(target, name);
    }

    @Override
    public void texImage2D(
            int target,
            int level,
            int format,
            int width,
            int height,
            int border,
            int pixelFormat,
            int type,
            ByteBuffer data) {
        int storageFormat = format == GL_RED && type == GL_UNSIGNED_BYTE ? GL_R8 : format;
        if (data == null
                && target == GL_TEXTURE_2D
                && (format == GL_RGBA || format == GL_RGBA8)
                && pixelFormat == GL_RGBA
                && type == GL_UNSIGNED_BYTE
                && width > 0
                && height > 0) {
            // PZ samples empty video storage before decoding its first frame. Keep initialization
            // in the same upload queue as decoded frames so it cannot overwrite a later frame.
            try (var empty = ClientPixels.transparent(width, height, BackendInternals.context().pixelStore)) {
                super.texImage2D(target, level, storageFormat, width, height, border, pixelFormat, type, empty.data());
            }
        } else {
            super.texImage2D(target, level, storageFormat, width, height, border, pixelFormat, type, data);
        }
    }

    @Override
    public void texImage2D(
            int target,
            int level,
            int format,
            int width,
            int height,
            int border,
            int pixelFormat,
            int type,
            long pixels) {
        var state = BackendInternals.context();
        if (state.boundPixelUnpackBuffer != 0) {
            super.texImage2D(target, level, format, width, height, border, pixelFormat, type, pixels);
            return;
        }
        if (pixels == 0) {
            texImage2D(target, level, format, width, height, border, pixelFormat, type, (ByteBuffer) null);
            return;
        }
        try (var client = ClientPixels.borrow(pixels, width, height, pixelFormat, type, state.pixelStore)) {
            texImage2D(target, level, format, width, height, border, client.format(), type, client.data());
        }
    }

    @Override
    public void texImage3D(
            int target,
            int level,
            int format,
            int width,
            int height,
            int depth,
            int border,
            int pixelFormat,
            int type,
            ByteBuffer data) {
        if (format != GL_RGB32I && format != GL_RGB32UI) {
            super.texImage3D(target, level, format, width, height, depth, border, pixelFormat, type, data);
            return;
        }
        if (level != 0 || border != 0 || (target != GL_TEXTURE_3D && target != GL_TEXTURE_2D_ARRAY))
            throw new UnsupportedOperationException(
                    "RGB32 integer allocation requires a borderless base-level volume or array");
        // SDL stores RGB32 integer pixels as RGBA32; the generic upstream uploader copies only three components.
        super.texImage3D(target, level, format, width, height, depth, border, pixelFormat, type, (ByteBuffer) null);
        if (data != null) {
            var state = BackendInternals.context();
            int name = textureBindings.get(state, state.activeTextureUnit, target);
            NamedTextureUploads.upload(name, level, 0, 0, 0, width, height, depth, pixelFormat, type, data, true);
        }
    }

    @Override
    public void texStorage2D(int target, int levels, int format, int width, int height) {
        super.texStorage2D(target, levels, format, width, height);
        var context = BackendInternals.context();
        int name = context.boundTextures[context.activeTextureUnit];
        if (BackendInternals.resources().getTextureHandle(name) != 0) textureViews.immutable(name);
    }

    @Override
    public void deleteTextures(int name) {
        textureBindings.deleted(BackendInternals.context(), name);
        textureObjects.deleted(name);
        bufferTextures.deleteTexture(name);
        long handle = BackendInternals.resources().getTextureHandle(name);
        textureViews.beforeDelete(name);
        super.deleteTextures(name);
        textureViews.deleted(name, handle);
    }

    @Override
    public void texParameteri(int target, int parameter, int value) {
        var context = BackendInternals.context();
        if (!textureViews.parameter(context.boundTextures[context.activeTextureUnit], parameter, value)) {
            super.texParameteri(target, parameter, value);
        }
    }

    @Override
    public void textureParameteri(int name, int target, int parameter, int value) {
        if (!textureViews.parameter(name, parameter, value)) super.textureParameteri(name, target, parameter, value);
    }

    @Override
    public int getTexParameteri(int target, int parameter) {
        var context = BackendInternals.context();
        var value = textureViews.parameter(context.boundTextures[context.activeTextureUnit], parameter);
        return value != null ? value : super.getTexParameteri(target, parameter);
    }

    @Override
    public void texParameterf(int target, int parameter, float value) {
        if (parameter == GL_DEPTH_STENCIL_TEXTURE_MODE) texParameteri(target, parameter, (int) value);
        else super.texParameterf(target, parameter, value);
    }

    @Override
    public void textureParameterf(int name, int target, int parameter, float value) {
        if (parameter == GL_DEPTH_STENCIL_TEXTURE_MODE) textureParameteri(name, target, parameter, (int) value);
        else super.textureParameterf(name, target, parameter, value);
    }

    @Override
    public float getTexParameterf(int target, int parameter) {
        var context = BackendInternals.context();
        var value = textureViews.parameter(context.boundTextures[context.activeTextureUnit], parameter);
        return value != null ? value.floatValue() : super.getTexParameterf(target, parameter);
    }

    @Override
    public void dispatchCompute(int x, int y, int z) {
        textureViews.beforeCompute();
        super.dispatchCompute(x, y, z);
    }

    @Override
    public void dispatchComputeIndirect(long offset) {
        textureViews.beforeCompute();
        super.dispatchComputeIndirect(offset);
    }

    @Override
    public int getError() {
        int result = error;
        error = 0;
        return result != 0 ? result : super.getError();
    }

    @Override
    public void uniformMatrix2(int location, boolean transpose, FloatBuffer value) {
        var checked = matrixUpload(location, value, 2);
        if (checked != null) super.uniformMatrix2(location, transpose, checked);
    }

    @Override
    public void uniformMatrix3(int location, boolean transpose, FloatBuffer value) {
        var checked = matrixUpload(location, value, 3);
        if (checked != null) super.uniformMatrix3(location, transpose, checked);
    }

    @Override
    public void uniformMatrix4(int location, boolean transpose, FloatBuffer value) {
        var checked = matrixUpload(location, value, 4);
        if (checked != null) super.uniformMatrix4(location, transpose, checked);
    }

    private FloatBuffer matrixUpload(int location, FloatBuffer value, int size) {
        if (location == -1) return null;
        var context = BackendInternals.context();
        try {
            return MatrixUniformUploads.prepare(context.boundProgramObj, location, size, value);
        } catch (IllegalArgumentException invalid) {
            if (error == 0) error = GL_INVALID_OPERATION;
            LOG.log(
                    System.Logger.Level.DEBUG,
                    "Rejected matrix uniform upload: program={0}, uniform={1}, location={2}, size={3}, count={4}: {5}",
                    context.boundProgram,
                    context.boundProgramObj == null
                            ? "<none>"
                            : context.boundProgramObj.locationToName.getOrDefault(location, "<unknown>"),
                    location,
                    size,
                    value.remaining() / (size * size),
                    invalid.getMessage());
            return null;
        }
    }

    @Override
    public void onFrameBegin() {
        // SDL may recycle the same native command-buffer address for a new recording.
        // Rebuild cached encoder bindings rather than treating pointer equality as state continuity.
        invalidateStateMirror();
        super.onFrameBegin();
        bufferTextures.beginFrame();
    }

    @Override
    public void shutdown() {
        // The upstream relay is static and would otherwise retain the host callback after device shutdown.
        DebugMessageRelay.setListener(null, 0L, false);
        queries.close();
        bufferTextures.close();
        programResources.clear();
        shaderPrograms.close();
        try {
            textureViews.close();
        } catch (RuntimeException | Error failure) {
            try {
                super.shutdown();
            } catch (RuntimeException | Error cleanup) {
                failure.addSuppressed(cleanup);
            }
            throw failure;
        }
        super.shutdown();
    }

    @Override
    public boolean isAvailable() {
        return BackendOptions.sdlGpuRequested() && SDLGPUGate.device().getDevice() != 0;
    }
}
