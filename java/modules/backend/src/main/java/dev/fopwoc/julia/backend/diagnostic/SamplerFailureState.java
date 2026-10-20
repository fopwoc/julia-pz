package dev.fopwoc.julia.backend.diagnostic;

import static org.lwjgl.util.spvc.Spvc.*;

import com.gtnewhorizons.angelica.sdlgpu.shader.ShaderManager.ProgramObject;
import dev.fopwoc.julia.backend.BackendInternals;
import java.util.Map;
import java.util.TreeMap;

/** Failure-only snapshots expose integer branch selectors without changing or specializing the shader. */
public final class SamplerFailureState {
    private SamplerFailureState() {}

    public static String describe(ProgramObject program) {
        var state = BackendInternals.context();
        var values = state.uniformStaging(program).uniformDataBySlot;
        Map<String, String> selectors = new TreeMap<>();
        for (var entry : program.locationToMemberInfo.int2ObjectEntrySet()) {
            var info = entry.getValue();
            if (info.vectorSize() != 1 || info.columns() != 1 || info.arrayLen() > 1) continue;
            int type = info.baseType();
            if (type != SPVC_BASETYPE_INT32 && type != SPVC_BASETYPE_UINT32 && type != SPVC_BASETYPE_BOOLEAN) continue;
            int location = entry.getIntKey();
            if (location < 0 || location >= values.length) continue;
            String name = program.locationToName.get(location);
            if (name == null) continue;
            float[] value = values[location];
            if (value == null) selectors.put(name, "0 (default)");
            else if (value.length > 0) {
                int bits = Float.floatToRawIntBits(value[0]);
                selectors.put(
                        name, type == SPVC_BASETYPE_UINT32 ? Integer.toUnsignedString(bits) : Integer.toString(bits));
            }
        }
        return "; program=" + state.boundProgram + "; scalar integer uniforms=" + selectors;
    }
}
