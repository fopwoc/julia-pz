package dev.fopwoc.julia.backend.vertex;

import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.util.spvc.Spvc.*;

import com.gtnewhorizons.angelica.glsm.backend.BackendManager;
import com.gtnewhorizons.angelica.sdlgpu.frame.ContextState;
import com.gtnewhorizons.angelica.sdlgpu.pipeline.PipelineCache;
import dev.fopwoc.julia.backend.Upstream;
import dev.fopwoc.julia.backend.sdl.SdlVertexInputs;
import java.util.WeakHashMap;
import org.lwjgl.sdl.SDL_GPUGraphicsPipelineCreateInfo;

/** Binding divisors retain all GLuint bits; attributes share the divisor of their selected VAO binding. */
public final class VertexInputs {
    private static final WeakHashMap<ContextState.VAOState, Integer> MODERN_LAYOUTS = new WeakHashMap<>();

    private VertexInputs() {}

    public static void modernAttribute(int attribute) {
        validateBinding(attribute);
        var vao = Upstream.context().currentVao;
        MODERN_LAYOUTS.put(vao, MODERN_LAYOUTS.getOrDefault(vao, 0) | 1 << attribute);
    }

    public static void modernBinding(int binding) {
        validateBinding(binding);
        var vao = Upstream.context().currentVao;
        for (int attribute = 0; attribute < ContextState.MAX_VERTEX_ATTRIBS; attribute++)
            if (vao.attribBinding[attribute] == binding) modernAttribute(attribute);
    }

    public static void legacyPointer(int attribute) {
        validateBinding(attribute);
        var vao = Upstream.context().currentVao;
        MODERN_LAYOUTS.put(vao, MODERN_LAYOUTS.getOrDefault(vao, 0) & ~(1 << attribute));
    }

    public static boolean preserveLayout(int attribute) {
        return (MODERN_LAYOUTS.getOrDefault(Upstream.context().currentVao, 0) & 1 << attribute) != 0;
    }

    public static void bindingDivisor(int binding, int divisor) {
        var state = Upstream.context();
        if (state.boundVAO == 0) throw new IllegalStateException("Vertex binding divisor requires a bound VAO");
        modernBinding(binding);
        setDivisor(state, binding, divisor);
    }

    public static void attributeDivisor(int attribute, int divisor) {
        validateBinding(attribute);
        // The legacy API restores the attribute's same-numbered binding before setting its divisor.
        BackendManager.RENDER_BACKEND.vertexAttribBinding(attribute, attribute);
        setDivisor(Upstream.context(), attribute, divisor);
    }

    private static void setDivisor(ContextState state, int binding, int divisor) {
        validateBinding(binding);
        var vao = state.currentVao;
        if (vao.bindingDivisor[binding] == divisor) return;
        vao.bindingDivisor[binding] = divisor;
        state.bumpAttribStateGen();
        state.pipeline.markInputDirty();
        vao.invalidateInputHash();
    }

    public static int divisor(int binding) {
        validateBinding(binding);
        return Upstream.context().currentVao.bindingDivisor[binding];
    }

    public static void validateBinding(int binding) {
        if (binding < 0 || binding >= ContextState.MAX_VERTEX_ATTRIBS)
            throw new IllegalArgumentException("Vertex binding index out of range: " + binding);
    }

    public static long pipelineHash(long original, ContextState state) {
        if (state == null) return original;
        // Upstream packs only sixteen divisor bits into its input hash.
        long hash = original;
        for (int divisor : state.currentVao.bindingDivisor) {
            hash ^= Integer.toUnsignedLong(divisor) + 0x9e3779b97f4a7c15L + (hash << 6) + (hash >>> 2);
        }
        return hash;
    }

    public static long createPipeline(long device, SDL_GPUGraphicsPipelineCreateInfo info) {
        var state = Upstream.context();
        int[] rates = new int[ContextState.MAX_VERTEX_ATTRIBS];
        var vao = state.currentVao;
        for (int attribute = 0; attribute < rates.length; attribute++) {
            int binding = vao.attribBinding[attribute];
            if (vao.attribEnabled[attribute] && Upstream.resources().getBufferHandle(vao.bindingBuffer[binding]) != 0)
                rates[attribute] = vao.bindingDivisor[binding];
        }
        return SdlVertexInputs.create(device, info, rates);
    }

    /**
     * Format of a live input whose array is disabled. The generic value is stored as floats, so integer inputs read its
     * bits; GL leaves such mismatched reads undefined, but Metal rejects a float format for an integer input outright.
     */
    public static int defaultFormat(int components, PipelineCache cache, int location) {
        int[] types = cache.shaderInputBaseType;
        int type = types != null && location < types.length ? types[location] : SPVC_BASETYPE_FP32;
        return switch (type) {
            case SPVC_BASETYPE_INT32 -> PipelineCache.mapVertexFormat(components, GL_INT, false, true);
            case SPVC_BASETYPE_UINT32 -> PipelineCache.mapVertexFormat(components, GL_UNSIGNED_INT, false, true);
            default -> PipelineCache.mapVertexFormat(components, GL_FLOAT, false, false);
        };
    }

    public static int replayDivisor(int divisor, int maximum) {
        if (Integer.compareUnsigned(divisor, maximum) > 0)
            throw new UnsupportedOperationException(
                    "Vertex-write replay cannot encode divisor " + Integer.toUnsignedString(divisor));
        return divisor;
    }
}
