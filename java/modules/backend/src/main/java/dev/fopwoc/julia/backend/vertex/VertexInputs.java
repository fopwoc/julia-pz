package dev.fopwoc.julia.backend.vertex;

import com.gtnewhorizons.angelica.glsm.backend.BackendManager;
import com.gtnewhorizons.angelica.sdlgpu.frame.ContextState;
import dev.fopwoc.julia.backend.BackendInternals;
import dev.fopwoc.julia.backend.platform.Platform;
import java.util.WeakHashMap;
import org.lwjgl.sdl.SDL_GPUGraphicsPipelineCreateInfo;

/** Binding divisors retain all GLuint bits; attributes share the divisor of their selected VAO binding. */
public final class VertexInputs {
    private static final WeakHashMap<ContextState.VAOState, Integer> MODERN_LAYOUTS = new WeakHashMap<>();

    private VertexInputs() {}

    public static void modernAttribute(int attribute) {
        validateBinding(attribute);
        var vao = BackendInternals.context().currentVao;
        MODERN_LAYOUTS.put(vao, MODERN_LAYOUTS.getOrDefault(vao, 0) | 1 << attribute);
    }

    public static void modernBinding(int binding) {
        validateBinding(binding);
        var vao = BackendInternals.context().currentVao;
        for (int attribute = 0; attribute < ContextState.MAX_VERTEX_ATTRIBS; attribute++)
            if (vao.attribBinding[attribute] == binding) modernAttribute(attribute);
    }

    public static void legacyPointer(int attribute) {
        validateBinding(attribute);
        var vao = BackendInternals.context().currentVao;
        MODERN_LAYOUTS.put(vao, MODERN_LAYOUTS.getOrDefault(vao, 0) & ~(1 << attribute));
    }

    public static boolean preserveLayout(int attribute) {
        return (MODERN_LAYOUTS.getOrDefault(BackendInternals.context().currentVao, 0) & 1 << attribute) != 0;
    }

    public static void bindingDivisor(int binding, int divisor) {
        var state = BackendInternals.context();
        if (state.boundVAO == 0) throw new IllegalStateException("Vertex binding divisor requires a bound VAO");
        modernBinding(binding);
        setDivisor(state, binding, divisor);
    }

    public static void attributeDivisor(int attribute, int divisor) {
        validateBinding(attribute);
        // The legacy API restores the attribute's same-numbered binding before setting its divisor.
        BackendManager.RENDER_BACKEND.vertexAttribBinding(attribute, attribute);
        setDivisor(BackendInternals.context(), attribute, divisor);
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
        return BackendInternals.context().currentVao.bindingDivisor[binding];
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
        var state = BackendInternals.context();
        int[] rates = new int[ContextState.MAX_VERTEX_ATTRIBS];
        var vao = state.currentVao;
        for (int attribute = 0; attribute < rates.length; attribute++) {
            int binding = vao.attribBinding[attribute];
            if (vao.attribEnabled[attribute]
                    && BackendInternals.resources().getBufferHandle(vao.bindingBuffer[binding]) != 0)
                rates[attribute] = vao.bindingDivisor[binding];
        }
        return Platform.current().createGraphicsPipeline(device, info, rates);
    }

    public static int replayDivisor(int divisor, int maximum) {
        if (Integer.compareUnsigned(divisor, maximum) > 0)
            throw new UnsupportedOperationException(
                    "Vertex-write replay cannot encode divisor " + Integer.toUnsignedString(divisor));
        return divisor;
    }
}
