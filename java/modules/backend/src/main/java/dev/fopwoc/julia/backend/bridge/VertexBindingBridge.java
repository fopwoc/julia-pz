package dev.fopwoc.julia.backend.bridge;

import static org.lwjgl.opengl.GL11.GL_FALSE;
import static org.lwjgl.opengl.GL11.GL_TRUE;
import static org.lwjgl.opengl.GL20.*;
import static org.lwjgl.opengl.GL30.GL_VERTEX_ATTRIB_ARRAY_INTEGER;
import static org.lwjgl.opengl.GL33.GL_VERTEX_ATTRIB_ARRAY_DIVISOR;
import static org.lwjgl.opengl.GL43.GL_VERTEX_ATTRIB_BINDING;
import static org.lwjgl.opengl.GL43.GL_VERTEX_ATTRIB_RELATIVE_OFFSET;

import com.gtnewhorizons.angelica.glsm.GLStateManager;
import com.gtnewhorizons.angelica.sdlgpu.frame.ContextState;
import dev.fopwoc.julia.backend.Upstream;
import dev.fopwoc.julia.backend.vertex.VertexInputs;

/** Divisor routing plus attribute-state queries answered from the bound vertex array. */
public final class VertexBindingBridge {
    private VertexBindingBridge() {}

    public static int glGetVertexAttribi(int attribute, int parameter) {
        var vao = attribute(attribute);
        int binding = vao.attribBinding[attribute];
        return switch (parameter) {
            case GL_VERTEX_ATTRIB_ARRAY_ENABLED -> vao.attribEnabled[attribute] ? GL_TRUE : GL_FALSE;
            case GL_VERTEX_ATTRIB_ARRAY_SIZE -> vao.attribSize[attribute];
            case GL_VERTEX_ATTRIB_ARRAY_STRIDE -> vao.attribStride[attribute];
            case GL_VERTEX_ATTRIB_ARRAY_TYPE -> vao.attribType[attribute];
            case GL_VERTEX_ATTRIB_ARRAY_NORMALIZED -> vao.attribNormalized[attribute] ? GL_TRUE : GL_FALSE;
            case GL_VERTEX_ATTRIB_ARRAY_INTEGER -> vao.attribIsInteger[attribute] ? GL_TRUE : GL_FALSE;
            case GL_VERTEX_ATTRIB_ARRAY_BUFFER_BINDING -> vao.attribVBO[attribute];
            case GL_VERTEX_ATTRIB_ARRAY_DIVISOR -> vao.bindingDivisor[binding];
            case GL_VERTEX_ATTRIB_BINDING -> binding;
            case GL_VERTEX_ATTRIB_RELATIVE_OFFSET -> vao.attribRelativeOffset[attribute];
            default -> throw new UnsupportedOperationException("Vertex attribute query not implemented: " + parameter);
        };
    }

    public static long glGetVertexAttribPointer(int attribute, int parameter) {
        if (parameter != GL_VERTEX_ATTRIB_ARRAY_POINTER)
            throw new IllegalArgumentException("Invalid vertex attribute pointer query: " + parameter);
        return attribute(attribute).attribOffset[attribute];
    }

    private static ContextState.VAOState attribute(int attribute) {
        if (attribute < 0 || attribute >= ContextState.MAX_VERTEX_ATTRIBS)
            throw new IllegalArgumentException("Vertex attribute out of range: " + attribute);
        return Upstream.context().currentVao;
    }

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
