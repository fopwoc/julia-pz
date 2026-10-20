package dev.fopwoc.julia.backend.layer;

import static org.lwjgl.opengl.GL12.*;
import static org.lwjgl.opengl.GL30.*;
import static org.lwjgl.opengl.GL43.GL_VERTEX_BINDING_DIVISOR;

import dev.fopwoc.julia.backend.Upstream;
import dev.fopwoc.julia.backend.vertex.BaseInstanceDraws;
import dev.fopwoc.julia.backend.vertex.VertexInputs;

/** Vertex attribute layout, full-width divisors, and base-instance offsets for instanced draws. */
public abstract class VertexLayer extends QueryLayer {
    @Override
    public void drawArraysInstanced(int mode, int first, int count, int instances) {
        if (count <= 0 || instances <= 0) return;
        try (var _ = BaseInstanceDraws.offsets(Upstream.context())) {
            super.drawArraysInstanced(mode, first, count, instances);
        }
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
    public void drawElementsInstanced(int mode, int count, int type, long indices, int instances) {
        if (count <= 0 || instances <= 0) return;
        try (var _ = BaseInstanceDraws.offsets(Upstream.context())) {
            super.drawElementsInstanced(mode, count, type, indices, instances);
        }
    }
}
