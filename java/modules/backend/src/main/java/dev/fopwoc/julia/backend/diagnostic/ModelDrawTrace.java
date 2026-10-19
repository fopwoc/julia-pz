package dev.fopwoc.julia.backend.diagnostic;

import dev.fopwoc.julia.backend.BackendInternals;
import java.util.Arrays;

/** Bounded CPU-state capture for small skinned-character viewports, disabled unless explicitly requested. */
public final class ModelDrawTrace {
    private static final System.Logger LOG = System.getLogger(ModelDrawTrace.class.getName());
    private static int remaining = Math.clamp(Integer.getInteger("julia.trace.models", 0), 0, 4096);

    private ModelDrawTrace() {}

    public static void record(int count) {
        if (remaining == 0) return;
        var state = BackendInternals.context();
        var program = state.boundProgramObj;
        if (program == null
                || !program.vertexSource.contains("MatrixPalette")
                || state.viewportW < 96
                || state.viewportW > 192
                || state.viewportH < state.viewportW * 1.8f
                || state.viewportH > state.viewportW * 2.2f) return;
        remaining--;
        var frame = BackendInternals.frames().frame();
        var pipeline = state.pipeline;
        var text = new StringBuilder("model frame=")
                .append(frame.frameNumber)
                .append(" count=")
                .append(count)
                .append(" program=")
                .append(state.boundProgram)
                .append(" fbo=")
                .append(state.boundFboId)
                .append(" target=")
                .append(frame.currentColorTarget)
                .append(':')
                .append(frame.currentDepthTarget)
                .append(" viewport=")
                .append(state.viewportX)
                .append(',')
                .append(state.viewportY)
                .append(',')
                .append(state.viewportW)
                .append(',')
                .append(state.viewportH)
                .append(" scissor=")
                .append(state.scissorEnabled)
                .append(':')
                .append(state.scissorX)
                .append(',')
                .append(state.scissorY)
                .append(',')
                .append(state.scissorW)
                .append(',')
                .append(state.scissorH)
                .append(" depth=")
                .append(pipeline.depthTestEnabled)
                .append(':')
                .append(pipeline.depthCompareOp)
                .append(':')
                .append(pipeline.depthWriteEnabled)
                .append(" stencil=")
                .append(pipeline.stencilTestEnabled)
                .append(':')
                .append(state.stencilRef)
                .append(':')
                .append(pipeline.stencilFrontCompareOp)
                .append(':')
                .append(pipeline.stencilCompareMask)
                .append(" cull=")
                .append(pipeline.cullEnabled)
                .append(':')
                .append(pipeline.cullFaceMode)
                .append(':')
                .append(pipeline.frontFace)
                .append(" mask=")
                .append(pipeline.colorWriteMask)
                .append(" texture=")
                .append(state.boundTextures[0])
                .append(" samplers=")
                .append(program.samplerTextureUnits);
        var staging = state.uniformStaging(program);
        for (var entry : program.locationToName.int2ObjectEntrySet()) {
            if (!entry.getValue().equals("ModelViewProjection")
                    && !entry.getValue().equals("TransformMatrix")
                    && !entry.getValue().equals("MatrixPalette")) continue;
            float[] values = staging.uniformDataBySlot[entry.getIntKey()];
            text.append(' ').append(entry.getValue()).append('=');
            if (values == null) text.append("unset");
            else
                text.append(values.length)
                        .append(':')
                        .append(Arrays.toString(Arrays.copyOf(values, Math.min(16, values.length))));
        }
        LOG.log(System.Logger.Level.INFO, text.toString());
    }
}
