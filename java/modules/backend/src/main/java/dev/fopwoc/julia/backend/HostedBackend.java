package dev.fopwoc.julia.backend;

import static org.lwjgl.opengl.GL43.GL_DEPTH_STENCIL_TEXTURE_MODE;

import com.gtnewhorizons.angelica.glsm.backend.BackendOptions;
import com.gtnewhorizons.angelica.sdlgpu.SDLGPUGate;
import com.gtnewhorizons.angelica.sdlgpu.SDLGPURenderBackend;
import com.gtnewhorizons.angelica.sdlgpu.util.DebugMessageRelay;
import dev.fopwoc.julia.backend.texture.TextureViews;
import java.nio.IntBuffer;

/** Selects the real SDL backend for an explicitly created device without Minecraft's display gate. */
public final class HostedBackend extends SDLGPURenderBackend {
    private final TextureViews textureViews = new TextureViews();

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
