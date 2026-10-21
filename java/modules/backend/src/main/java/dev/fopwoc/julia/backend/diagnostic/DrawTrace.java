package dev.fopwoc.julia.backend.diagnostic;

import static org.lwjgl.opengl.GL12.GL_TEXTURE_BASE_LEVEL;
import static org.lwjgl.opengl.GL12.GL_TEXTURE_MAX_LEVEL;
import static org.lwjgl.util.spvc.Spvc.*;

import com.gtnewhorizons.angelica.sdlgpu.frame.ContextState;
import com.gtnewhorizons.angelica.sdlgpu.sampler.SamplerLookup;
import com.gtnewhorizons.angelica.sdlgpu.shader.ShaderManager.ProgramObject;
import com.gtnewhorizons.angelica.sdlgpu.shader.ShaderManager.UniformMemberInfo;
import dev.fopwoc.julia.backend.Subsystems;
import dev.fopwoc.julia.backend.Upstream;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.FloatBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

/** Opt-in CPU-side graphics snapshots. Never reads back GPU resources or changes render state. */
public final class DrawTrace implements AutoCloseable {
    // Frame index and two bounded content-addressed source paths accompany each snapshot.
    private static final int SOURCE_HEADER_BYTES = 256;
    private final boolean enabled;
    private TraceBudget budget;
    private TraceShaders shaders;
    private Path output;
    private long frame;
    private BufferedWriter writer;

    public DrawTrace() {
        this(Boolean.getBoolean("julia.drawTrace"));
    }

    DrawTrace(boolean enabled) {
        this.enabled = enabled;
    }

    public void open(Path output) {
        if (!enabled) return;
        if (writer != null) throw new IllegalStateException("Draw trace is already open");
        this.output = output;
        budget = new TraceBudget(2048, 64, 16 * 1024 * 1024);
        shaders = new TraceShaders(output.resolve("draw-shaders"));
        try {
            writer = Files.newBufferedWriter(output.resolve("draw-trace.txt"), StandardCharsets.UTF_8);
            writer.write("Julia draw trace: CPU snapshots; max 2048 states,64 per program,16 MiB records\n");
            writer.write("captureFrame=" + frame + "\n");
            writer.flush();
        } catch (IOException failure) {
            throw new IllegalStateException("Cannot open draw trace", failure);
        }
    }

    /** A request file restarts capture on the render thread after the scene has reached the state under investigation. */
    public void frameBoundary() {
        frame++;
        if (output == null) return;
        try {
            if (!Files.deleteIfExists(output.resolve("draw-trace.request"))) return;
        } catch (IOException failure) {
            throw new IllegalStateException("Cannot consume draw-trace request", failure);
        }
        close();
        open(output);
    }

    public static void beforeDraw(ContextState state) {
        Subsystems.current().drawTrace().capture(state);
    }

    private void capture(ContextState state) {
        if (writer == null || !budget.available(state.boundProgram)) return;
        var program = Upstream.shaders().getProgram(state.boundProgram);
        if (program == null || !program.linked) return;
        var staging = state.uniformStaging(program);
        if (staging.uniformDataBySlot == null) return;
        var controls = new StringBuilder();
        var snapshot = new StringBuilder();
        var pipeline = state.pipeline;
        String header =
                "program=" + state.boundProgram + " fragment=" + Integer.toHexString(program.fragmentSource.hashCode())
                        + " fbo=" + state.boundFboId + " viewport=" + state.viewportX + "," + state.viewportY + ","
                        + state.viewportW + "," + state.viewportH + " depth=" + pipeline.depthTestEnabled + "/"
                        + pipeline.depthWriteEnabled + "/" + pipeline.depthCompareOp + " masks="
                        + Long.toHexString(Subsystems.current().colorMasks().packed()) + "\n";
        snapshot.append(header);
        controls.append(header);
        snapshot.append("blend=")
                .append(Arrays.toString(pipeline.blendEnabledPerDrawBuffer))
                .append(" src=")
                .append(Arrays.toString(pipeline.srcColorFactors))
                .append(" dst=")
                .append(Arrays.toString(pipeline.dstColorFactors))
                .append(" srcA=")
                .append(Arrays.toString(pipeline.srcAlphaFactors))
                .append(" dstA=")
                .append(Arrays.toString(pipeline.dstAlphaFactors))
                .append('\n');
        controls.append(snapshot.substring(header.length()));
        var fbo = Upstream.resources().getFbo(state.boundFboId);
        if (fbo != null)
            snapshot.append("outputs=")
                    .append(Arrays.toString(fbo.drawBuffers))
                    .append(" textures=")
                    .append(Arrays.toString(fbo.colorGlIds))
                    .append(" levels=")
                    .append(Arrays.toString(fbo.colorLevels))
                    .append('\n');
        for (int location = 0; location < staging.uniformDataBySlot.length; location++) {
            String name = program.locationToName.get(location);
            if (name == null) continue;
            var member = program.anyBlockMemberInfo(location);
            if (member == null) member = program.locationToMemberInfo.get(location);
            float[] values = staging.uniformDataBySlot[location];
            String line = "uniform " + name + "=" + values(values, member) + " vs="
                    + packed(staging.vsUniformFb, program.vsInfoBySlot, location) + " fs="
                    + packed(staging.fsUniformFb, program.fsInfoBySlot, location) + "\n";
            snapshot.append(line);
            // Camera matrices, positions, and light arrays vary per draw and would hide clipping/control changes.
            if (member != null && member.columns() == 1 && member.arrayLen() == 1 && member.vectorSize() != 3)
                controls.append(name).append('=').append(values(values, member)).append('\n');
        }
        samplers(snapshot, program, state, false);
        samplers(snapshot, program, state, true);
        snapshot.append('\n');
        String text = snapshot.toString();
        if (!budget.reserve(
                state.boundProgram,
                controls.toString(),
                text.getBytes(StandardCharsets.UTF_8).length + SOURCE_HEADER_BYTES)) return;
        try {
            writer.write("drawFrame=" + frame + " vertexSource=" + shaders.write(program.vertexSource)
                    + " fragmentSource=" + shaders.write(program.fragmentSource) + "\n");
            writer.write(text);
            writer.flush();
        } catch (IOException failure) {
            throw new IllegalStateException("Cannot write draw trace", failure);
        }
    }

    private static String values(float[] data, UniformMemberInfo member) {
        if (data == null) return "default-zero";
        var out = new StringBuilder("[");
        for (int i = 0; i < Math.min(data.length, 16); i++) {
            if (i != 0) out.append(',');
            int type = member == null ? SPVC_BASETYPE_FP32 : member.baseType();
            switch (type) {
                case SPVC_BASETYPE_INT32, SPVC_BASETYPE_BOOLEAN -> out.append(Float.floatToRawIntBits(data[i]));
                case SPVC_BASETYPE_UINT32 -> out.append(Integer.toUnsignedString(Float.floatToRawIntBits(data[i])));
                default -> out.append(data[i]);
            }
        }
        if (data.length > 16) out.append(",... length=").append(data.length);
        return out.append(']').toString();
    }

    private static String packed(FloatBuffer buffer, UniformMemberInfo[] members, int location) {
        if (buffer == null || location >= members.length || members[location] == null) return "-";
        var member = members[location];
        int count = Math.min(member.vectorSize(), buffer.capacity() - member.offset() / 4);
        var data = new float[Math.max(0, count)];
        for (int i = 0; i < data.length; i++) data[i] = buffer.get(member.offset() / 4 + i);
        return values(data, member) + "@" + member.offset();
    }

    private static void samplers(StringBuilder out, ProgramObject program, ContextState state, boolean fragment) {
        var own = Subsystems.current();
        int[] units = SamplerLookup.resolvedUnits(program, fragment);
        var names = fragment ? program.fragmentSamplerNames : program.vertexSamplerNames;
        for (int slot = 0; slot < units.length; slot++) {
            int unit = units[slot], texture = own.bufferTextures().samplerBinding(state, unit, slot, fragment);
            var meta = Upstream.resources().getTextureMeta(texture);
            out.append(fragment ? "fs " : "vs ")
                    .append("sampler ")
                    .append(slot)
                    .append(' ')
                    .append(slot < names.size() ? names.get(slot) : "?")
                    .append(" unit=")
                    .append(unit)
                    .append(" texture=")
                    .append(texture)
                    .append(" storage=")
                    .append(meta)
                    .append(" base=")
                    .append(own.textureViews().parameter(texture, GL_TEXTURE_BASE_LEVEL))
                    .append(" max=")
                    .append(own.textureViews().parameter(texture, GL_TEXTURE_MAX_LEVEL))
                    .append('\n');
        }
    }

    @Override
    public void close() {
        if (writer == null) return;
        try {
            writer.close();
            writer = null;
        } catch (IOException failure) {
            throw new IllegalStateException("Cannot close draw trace", failure);
        }
    }
}
