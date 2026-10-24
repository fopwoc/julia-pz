package dev.fopwoc.julia.backend.diagnostic;

import com.gtnewhorizons.angelica.sdlgpu.frame.ContextState;
import dev.fopwoc.julia.backend.Upstream;

/** Logical buffer identities and ranges let a later explicit request inspect only the relevant GPU bytes. */
final class TraceBuffers {
    private TraceBuffers() {}

    static void append(StringBuilder out, ContextState state) {
        out.append("indirectBuffer=")
                .append(state.boundIndirectBuffer)
                .append(" dispatchBuffer=")
                .append(state.boundDispatchIndirectBuffer)
                .append('\n');
        for (int slot = 0; slot < state.boundSsboByIndex.length; slot++) {
            int name = state.boundSsboByIndex[slot];
            if (name != 0)
                out.append("ssbo ")
                        .append(slot)
                        .append(" buffer=")
                        .append(name)
                        .append(" bytes=")
                        .append(Upstream.resources().getBufferSize(name))
                        .append('\n');
        }
        var vao = state.currentVao;
        if (vao == null) return;
        out.append("vao=")
                .append(state.boundVAO)
                .append(" elementBuffer=")
                .append(vao.elementBuffer)
                .append('\n');
        for (int attribute = 0; attribute < vao.attribEnabled.length; attribute++) {
            if (!vao.attribEnabled[attribute]) continue;
            int binding = vao.attribBinding[attribute], name = vao.bindingBuffer[binding];
            out.append("attribute ")
                    .append(attribute)
                    .append(" components=")
                    .append(vao.attribSize[attribute])
                    .append(" type=")
                    .append(vao.attribType[attribute])
                    .append(" integer=")
                    .append(vao.attribIsInteger[attribute])
                    .append(" normalized=")
                    .append(vao.attribNormalized[attribute])
                    .append(" binding=")
                    .append(binding)
                    .append(" buffer=")
                    .append(name)
                    .append(" bytes=")
                    .append(Upstream.resources().getBufferSize(name))
                    .append(" offset=")
                    .append(vao.bindingOffset[binding])
                    .append(" relative=")
                    .append(vao.attribRelativeOffset[attribute])
                    .append(" stride=")
                    .append(vao.bindingStride[binding])
                    .append(" divisor=")
                    .append(vao.bindingDivisor[binding])
                    .append(" legacyBuffer=")
                    .append(vao.attribVBO[attribute])
                    .append('\n');
        }
    }
}
