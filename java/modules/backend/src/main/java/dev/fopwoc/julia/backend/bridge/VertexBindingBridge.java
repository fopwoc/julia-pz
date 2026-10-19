package dev.fopwoc.julia.backend.bridge;

import com.gtnewhorizons.angelica.glsm.GLStateManager;
import dev.fopwoc.julia.backend.vertex.VertexInputs;

public final class VertexBindingBridge {
    private VertexBindingBridge() {}

    public static void glVertexBindingDivisor(int binding, int divisor) {
        VertexInputs.bindingDivisor(binding, divisor);
    }

    public static void glVertexAttribDivisor(int attribute, int divisor) {
        VertexInputs.validateBinding(attribute);
        GLStateManager.glVertexAttribDivisor(attribute, divisor);
        // GLSM may skip an equal legacy value after a modern binding operation changed the binding.
        VertexInputs.attributeDivisor(attribute, divisor);
    }

    public static void glVertexAttribDivisorARB(int attribute, int divisor) {
        glVertexAttribDivisor(attribute, divisor);
    }
}
