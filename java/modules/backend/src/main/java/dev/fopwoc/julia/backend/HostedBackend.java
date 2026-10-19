package dev.fopwoc.julia.backend;

import static org.lwjgl.opengl.GL11.GL_INVALID_OPERATION;
import static org.lwjgl.opengl.GL11.GL_RED;
import static org.lwjgl.opengl.GL11.GL_RGBA;
import static org.lwjgl.opengl.GL11.GL_RGBA8;
import static org.lwjgl.opengl.GL11.GL_TEXTURE_2D;
import static org.lwjgl.opengl.GL11.GL_UNSIGNED_BYTE;
import static org.lwjgl.opengl.GL20.GL_ACTIVE_UNIFORMS;
import static org.lwjgl.opengl.GL20.GL_ACTIVE_UNIFORM_MAX_LENGTH;
import static org.lwjgl.opengl.GL20.GL_ATTACHED_SHADERS;
import static org.lwjgl.opengl.GL20.GL_LINK_STATUS;
import static org.lwjgl.opengl.GL30.GL_R8;
import static org.lwjgl.opengl.GL43.GL_DEPTH_STENCIL_TEXTURE_MODE;

import com.gtnewhorizons.angelica.glsm.backend.BackendOptions;
import com.gtnewhorizons.angelica.sdlgpu.SDLGPUGate;
import com.gtnewhorizons.angelica.sdlgpu.SDLGPURenderBackend;
import com.gtnewhorizons.angelica.sdlgpu.util.DebugMessageRelay;
import dev.fopwoc.julia.backend.shader.MatrixUniformUploads;
import dev.fopwoc.julia.backend.shader.ProgramResourceCache;
import dev.fopwoc.julia.backend.shader.ShaderPrograms;
import dev.fopwoc.julia.backend.texture.ClientPixels;
import dev.fopwoc.julia.backend.texture.TextureViews;
import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

/** Selects the real SDL backend for an explicitly created device without Minecraft's display gate. */
public final class HostedBackend extends SDLGPURenderBackend {
    private static final System.Logger LOG = System.getLogger(HostedBackend.class.getName());
    private int error;

    private final ShaderPrograms shaderPrograms = new ShaderPrograms();
    private final TextureViews textureViews = new TextureViews();
    private final ProgramResourceCache programResources = new ProgramResourceCache();

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
        return super.getUniformLocation(program, shaderPrograms.backendUniformName(program, name.toString()));
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
        return shaderPrograms.publicUniformName(program, super.getActiveUniform(program, index, 0, sizeType));
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
            super.linkProgram(program);
            if (super.getProgrami(program, GL_LINK_STATUS) != 0) shaderPrograms.initializeUniforms(program);
        }
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
    public void bindTexture(int target, int name) {
        textureViews.bound(name);
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
    public void texStorage2D(int target, int levels, int format, int width, int height) {
        super.texStorage2D(target, levels, format, width, height);
        var context = BackendInternals.context();
        int name = context.boundTextures[context.activeTextureUnit];
        if (BackendInternals.resources().getTextureHandle(name) != 0) textureViews.immutable(name);
    }

    @Override
    public void deleteTextures(int name) {
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
    }

    @Override
    public void shutdown() {
        // The upstream relay is static and would otherwise retain the host callback after device shutdown.
        DebugMessageRelay.setListener(null, 0L, false);
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
