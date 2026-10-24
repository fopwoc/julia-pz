package dev.fopwoc.julia.backend;

import static org.lwjgl.opengl.GL12.*;
import static org.lwjgl.opengl.GL14.GL_DECR_WRAP;
import static org.lwjgl.opengl.GL14.GL_INCR_WRAP;
import static org.lwjgl.opengl.GL20.*;
import static org.lwjgl.opengl.GL20.GL_MAX_COMBINED_TEXTURE_IMAGE_UNITS;
import static org.lwjgl.opengl.GL20.GL_MAX_TEXTURE_IMAGE_UNITS;
import static org.lwjgl.opengl.GL20.GL_MAX_VERTEX_TEXTURE_IMAGE_UNITS;
import static org.lwjgl.opengl.GL21.GL_PIXEL_PACK_BUFFER;
import static org.lwjgl.opengl.GL21.GL_PIXEL_PACK_BUFFER_BINDING;
import static org.lwjgl.opengl.GL21.GL_PIXEL_UNPACK_BUFFER;
import static org.lwjgl.opengl.GL21.GL_PIXEL_UNPACK_BUFFER_BINDING;
import static org.lwjgl.opengl.GL30.*;
import static org.lwjgl.opengl.GL31.GL_COPY_READ_BUFFER;
import static org.lwjgl.opengl.GL31.GL_COPY_WRITE_BUFFER;
import static org.lwjgl.opengl.GL31.GL_MAX_TEXTURE_BUFFER_SIZE;
import static org.lwjgl.opengl.GL31.GL_TEXTURE_BINDING_BUFFER;
import static org.lwjgl.opengl.GL31.GL_TEXTURE_BUFFER;
import static org.lwjgl.opengl.GL31.GL_UNIFORM_BUFFER;
import static org.lwjgl.opengl.GL31.GL_UNIFORM_BUFFER_BINDING;
import static org.lwjgl.opengl.GL40.GL_DRAW_INDIRECT_BUFFER_BINDING;
import static org.lwjgl.opengl.GL40.GL_PATCH_VERTICES;
import static org.lwjgl.opengl.GL41.GL_MAX_VIEWPORTS;
import static org.lwjgl.opengl.GL41.GL_NUM_PROGRAM_BINARY_FORMATS;
import static org.lwjgl.opengl.GL41.GL_PROGRAM_BINARY_FORMATS;
import static org.lwjgl.opengl.GL43.GL_DISPATCH_INDIRECT_BUFFER;
import static org.lwjgl.opengl.GL43.GL_DISPATCH_INDIRECT_BUFFER_BINDING;
import static org.lwjgl.opengl.GL43.GL_MAX_VERTEX_ATTRIB_BINDINGS;
import static org.lwjgl.opengl.GL43.GL_SHADER_STORAGE_BUFFER;
import static org.lwjgl.opengl.GL43.GL_SHADER_STORAGE_BUFFER_BINDING;
import static org.lwjgl.opengl.GL43.GL_TEXTURE_BUFFER_OFFSET_ALIGNMENT;

import com.gtnewhorizons.angelica.glsm.backend.BackendOptions;
import com.gtnewhorizons.angelica.sdlgpu.SDLGPUGate;
import com.gtnewhorizons.angelica.sdlgpu.frame.ContextState;
import com.gtnewhorizons.angelica.sdlgpu.resource.FormatMap;
import com.gtnewhorizons.angelica.sdlgpu.util.DebugMessageRelay;
import dev.fopwoc.julia.backend.layer.ShaderLayer;
import dev.fopwoc.julia.backend.shader.SamplerResources;
import dev.fopwoc.julia.backend.texture.TextureBindings;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;

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
            // Upstream tracks these bindings but answers no query for them; mods save and restore them around passes.
            case GL_SHADER_STORAGE_BUFFER_BINDING -> Upstream.context().getBoundBuffer(GL_SHADER_STORAGE_BUFFER);
            case GL_UNIFORM_BUFFER_BINDING -> Upstream.context().getBoundBuffer(GL_UNIFORM_BUFFER);
            case GL_PIXEL_PACK_BUFFER_BINDING -> Upstream.context().getBoundBuffer(GL_PIXEL_PACK_BUFFER);
            case GL_PIXEL_UNPACK_BUFFER_BINDING -> Upstream.context().getBoundBuffer(GL_PIXEL_UNPACK_BUFFER);
            case GL_DISPATCH_INDIRECT_BUFFER_BINDING -> Upstream.context().getBoundBuffer(GL_DISPATCH_INDIRECT_BUFFER);
            case GL_RENDERBUFFER_BINDING -> subsystems().reported().renderbuffer;
            case GL_PROGRAM_BINARY_FORMATS -> 0;
            // Volume readback is not implemented; TextureLayer accepts only the default layout.
            case GL_PACK_IMAGE_HEIGHT, GL_PACK_SKIP_IMAGES -> 0;
            // Tessellation is unsupported; this is GL's initial value.
            case GL_PATCH_VERTICES -> 3;
            case GL_STENCIL_BACK_FUNC -> glCompare(Upstream.context().pipeline.stencilBackCompareOp);
            case GL_STENCIL_BACK_FAIL -> glStencilOp(Upstream.context().pipeline.stencilBackFailOp);
            case GL_STENCIL_BACK_PASS_DEPTH_FAIL -> glStencilOp(Upstream.context().pipeline.stencilBackDepthFailOp);
            case GL_STENCIL_BACK_PASS_DEPTH_PASS -> glStencilOp(Upstream.context().pipeline.stencilBackPassOp);
            case GL_DRAW_BUFFER0,
                    GL_DRAW_BUFFER1,
                    GL_DRAW_BUFFER2,
                    GL_DRAW_BUFFER3,
                    GL_DRAW_BUFFER4,
                    GL_DRAW_BUFFER5,
                    GL_DRAW_BUFFER6,
                    GL_DRAW_BUFFER7 -> drawBufferOutput(parameter - GL_DRAW_BUFFER0);
            case GL_UNPACK_IMAGE_HEIGHT -> subsystems().volumeUnpack().imageHeight;
            case GL_UNPACK_SKIP_IMAGES -> subsystems().volumeUnpack().skipImages;
            case GL_UNPACK_SWAP_BYTES -> subsystems().volumeUnpack().swapBytes ? GL_TRUE : GL_FALSE;
            case GL_MAX_VERTEX_ATTRIB_BINDINGS -> ContextState.MAX_VERTEX_ATTRIBS;
            // SDL GPU has one viewport and scissor per pass; see ViewportBridge.
            case GL_MAX_VIEWPORTS -> 1;
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
    public void getInteger(int parameter, IntBuffer values) {
        // Julia reports no program binary formats, so the format list is empty.
        if (parameter == GL_PROGRAM_BINARY_FORMATS) return;
        if (answers(parameter)) values.put(values.position(), getInteger(parameter));
        else super.getInteger(parameter, values);
    }

    @Override
    public float getFloat(int parameter) {
        return switch (parameter) {
            case GL_POLYGON_OFFSET_FACTOR -> subsystems().reported().polygonOffsetFactor;
            case GL_POLYGON_OFFSET_UNITS -> subsystems().reported().polygonOffsetUnits;
            // GLSM fills float arrays through this scalar form; both range ends are 1.
            case GL_ALIASED_LINE_WIDTH_RANGE -> 1;
            default -> answers(parameter) ? getInteger(parameter) : super.getFloat(parameter);
        };
    }

    @Override
    public void getFloat(int parameter, FloatBuffer values) {
        int at = values.position();
        if (parameter == GL_ALIASED_LINE_WIDTH_RANGE) {
            // SDL GPU rasterizes only one-pixel lines.
            values.put(at, 1).put(at + 1, 1);
        } else if (parameter == GL_POLYGON_OFFSET_FACTOR || parameter == GL_POLYGON_OFFSET_UNITS || answers(parameter))
            values.put(at, getFloat(parameter));
        else super.getFloat(parameter, values);
    }

    /** Integer state this backend answers itself, so the vector and float forms agree with {@link #getInteger}. */
    private static boolean answers(int parameter) {
        return switch (parameter) {
            case GL_SHADER_STORAGE_BUFFER_BINDING,
                    GL_UNIFORM_BUFFER_BINDING,
                    GL_PIXEL_PACK_BUFFER_BINDING,
                    GL_PIXEL_UNPACK_BUFFER_BINDING,
                    GL_DISPATCH_INDIRECT_BUFFER_BINDING,
                    GL_RENDERBUFFER_BINDING,
                    GL_PACK_IMAGE_HEIGHT,
                    GL_PACK_SKIP_IMAGES,
                    GL_PATCH_VERTICES,
                    GL_STENCIL_BACK_FUNC,
                    GL_STENCIL_BACK_FAIL,
                    GL_STENCIL_BACK_PASS_DEPTH_FAIL,
                    GL_STENCIL_BACK_PASS_DEPTH_PASS,
                    GL_DRAW_BUFFER0,
                    GL_DRAW_BUFFER1,
                    GL_DRAW_BUFFER2,
                    GL_DRAW_BUFFER3,
                    GL_DRAW_BUFFER4,
                    GL_DRAW_BUFFER5,
                    GL_DRAW_BUFFER6,
                    GL_DRAW_BUFFER7 -> true;
            default -> false;
        };
    }

    private int drawBufferOutput(int index) {
        // Output 0 is upstream's single draw buffer; later outputs exist only on framebuffer objects.
        if (index == 0) return super.getInteger(GL_DRAW_BUFFER);
        var state = Upstream.context();
        if (state.boundFboId == 0 || state.boundFboId == state.defaultFboId) return GL_NONE;
        var fbo = Upstream.resources().getFbo(state.boundFboId);
        if (fbo == null || index >= fbo.drawBuffers.length || fbo.drawBuffers[index] < 0) return GL_NONE;
        return GL_COLOR_ATTACHMENT0 + fbo.drawBuffers[index];
    }

    // Upstream stores SDL ops; mapping each GL value forward finds the one a query must report.
    private static int glStencilOp(int sdl) {
        for (int op : new int[] {GL_KEEP, GL_ZERO, GL_REPLACE, GL_INCR, GL_DECR, GL_INVERT, GL_INCR_WRAP, GL_DECR_WRAP})
            if (FormatMap.mapStencilOp(op) == sdl) return op;
        throw new IllegalStateException("Unknown SDL stencil op: " + sdl);
    }

    private static int glCompare(int sdl) {
        for (int function = GL_NEVER; function <= GL_ALWAYS; function++)
            if (FormatMap.mapCompareOp(function) == sdl) return function;
        throw new IllegalStateException("Unknown SDL compare op: " + sdl);
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
