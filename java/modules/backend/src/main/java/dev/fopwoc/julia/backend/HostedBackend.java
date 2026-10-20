package dev.fopwoc.julia.backend;

import static org.lwjgl.opengl.GL12.*;
import static org.lwjgl.opengl.GL20.GL_MAX_COMBINED_TEXTURE_IMAGE_UNITS;
import static org.lwjgl.opengl.GL20.GL_MAX_TEXTURE_IMAGE_UNITS;
import static org.lwjgl.opengl.GL20.GL_MAX_VERTEX_TEXTURE_IMAGE_UNITS;
import static org.lwjgl.opengl.GL30.*;
import static org.lwjgl.opengl.GL31.GL_COPY_READ_BUFFER;
import static org.lwjgl.opengl.GL31.GL_COPY_WRITE_BUFFER;
import static org.lwjgl.opengl.GL31.GL_MAX_TEXTURE_BUFFER_SIZE;
import static org.lwjgl.opengl.GL31.GL_TEXTURE_BINDING_BUFFER;
import static org.lwjgl.opengl.GL31.GL_TEXTURE_BUFFER;
import static org.lwjgl.opengl.GL40.GL_DRAW_INDIRECT_BUFFER_BINDING;
import static org.lwjgl.opengl.GL41.GL_NUM_PROGRAM_BINARY_FORMATS;
import static org.lwjgl.opengl.GL43.GL_MAX_VERTEX_ATTRIB_BINDINGS;
import static org.lwjgl.opengl.GL43.GL_TEXTURE_BUFFER_OFFSET_ALIGNMENT;

import com.gtnewhorizons.angelica.glsm.backend.BackendOptions;
import com.gtnewhorizons.angelica.sdlgpu.SDLGPUGate;
import com.gtnewhorizons.angelica.sdlgpu.frame.ContextState;
import com.gtnewhorizons.angelica.sdlgpu.util.DebugMessageRelay;
import dev.fopwoc.julia.backend.layer.ShaderLayer;
import dev.fopwoc.julia.backend.shader.SamplerResources;
import dev.fopwoc.julia.backend.texture.TextureBindings;

/**
 * Selects the real SDL backend for an explicitly created device without Minecraft's display gate. Each GL domain lives
 * in its own layer in {@code backend.layer}; this class keeps only cross-domain calls and lifecycle.
 */
public final class HostedBackend extends ShaderLayer {
    @Override
    public void flush() {
        Upstream.commandBufferOutsidePasses();
        Upstream.submitPendingCommands();
    }

    @Override
    public void finish() {
        flush();
        super.finish();
    }

    @Override
    public int getInteger(int parameter) {
        int target = TextureBindings.queryTarget(parameter);
        if (target != 0) {
            var state = Upstream.context();
            return subsystems().textureBindings().get(state, state.activeTextureUnit, target);
        }
        return switch (parameter) {
            // Julia does not expose OpenGL program binaries; its internal shader cache is separate.
            case GL_NUM_PROGRAM_BINARY_FORMATS -> 0;
            case GL_MAX_TEXTURE_IMAGE_UNITS, GL_MAX_VERTEX_TEXTURE_IMAGE_UNITS -> SamplerResources.LIMIT;
            case GL_MAX_COMBINED_TEXTURE_IMAGE_UNITS -> TextureBindings.UNIT_COUNT;
            case GL_COPY_READ_BUFFER -> Upstream.context().boundCopyReadBuffer;
            case GL_COPY_WRITE_BUFFER -> Upstream.context().boundCopyWriteBuffer;
            case GL_DRAW_INDIRECT_BUFFER_BINDING -> Upstream.context().boundIndirectBuffer;
            case GL_UNPACK_IMAGE_HEIGHT -> subsystems().volumeUnpack().imageHeight;
            case GL_UNPACK_SKIP_IMAGES -> subsystems().volumeUnpack().skipImages;
            case GL_UNPACK_SWAP_BYTES -> subsystems().volumeUnpack().swapBytes ? GL_TRUE : GL_FALSE;
            case GL_MAX_VERTEX_ATTRIB_BINDINGS -> ContextState.MAX_VERTEX_ATTRIBS;
            case GL_MAX_TEXTURE_BUFFER_SIZE -> subsystems().bufferTextures().limit();
            case GL_TEXTURE_BUFFER_OFFSET_ALIGNMENT ->
                subsystems().bufferTextures().alignment();
            case GL_TEXTURE_BINDING_BUFFER ->
                subsystems().bufferTextures().boundTexture(Upstream.context().activeTextureUnit);
            case GL_TEXTURE_BUFFER -> subsystems().bufferTextures().boundBuffer();
            default -> super.getInteger(parameter);
        };
    }

    @Override
    public void onFrameBegin() {
        // SDL may recycle the same native command-buffer address for a new recording.
        // Rebuild cached encoder bindings rather than treating pointer equality as state continuity.
        invalidateStateMirror();
        super.onFrameBegin();
        subsystems().bufferTextures().beginFrame();
    }

    @Override
    public void shutdown() {
        // The upstream relay is static and would otherwise retain the host callback after device shutdown.
        DebugMessageRelay.setListener(null, 0L, false);
        try {
            subsystems().close();
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
