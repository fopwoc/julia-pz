package dev.fopwoc.julia.backend;

import static org.lwjgl.opengl.GL11.GL_INVALID_OPERATION;
import static org.lwjgl.opengl.GL11.GL_RED;
import static org.lwjgl.opengl.GL11.GL_UNSIGNED_BYTE;
import static org.lwjgl.opengl.GL30.GL_R8;
import static org.lwjgl.opengl.GL43.GL_DEPTH_STENCIL_TEXTURE_MODE;

import com.gtnewhorizons.angelica.glsm.backend.BackendOptions;
import com.gtnewhorizons.angelica.sdlgpu.SDLGPUGate;
import com.gtnewhorizons.angelica.sdlgpu.SDLGPURenderBackend;
import com.gtnewhorizons.angelica.sdlgpu.util.DebugMessageRelay;
import dev.fopwoc.julia.backend.shader.MatrixUniformUploads;
import dev.fopwoc.julia.backend.shader.ProgramResourceCache;
import dev.fopwoc.julia.backend.texture.TextureViews;
import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;

/** Selects the real SDL backend for an explicitly created device without Minecraft's display gate. */
public final class HostedBackend extends SDLGPURenderBackend {
    private static final System.Logger LOG = System.getLogger(HostedBackend.class.getName());
    private int error;

    private final TextureViews textureViews = new TextureViews();
    private final ProgramResourceCache programResources = new ProgramResourceCache();

    public ProgramResourceCache programResources() {
        return programResources;
    }

    public TextureViews textureViews() {
        return textureViews;
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
        super.texImage2D(
                target,
                level,
                format == GL_RED && type == GL_UNSIGNED_BYTE ? GL_R8 : format,
                width,
                height,
                border,
                pixelFormat,
                type,
                data);
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
        try {
            return MatrixUniformUploads.prepare(BackendInternals.context().boundProgramObj, location, size, value);
        } catch (IllegalArgumentException invalid) {
            if (error == 0) error = GL_INVALID_OPERATION;
            LOG.log(
                    System.Logger.Level.DEBUG,
                    "Rejected matrix uniform upload at location {0}: {1}",
                    location,
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
