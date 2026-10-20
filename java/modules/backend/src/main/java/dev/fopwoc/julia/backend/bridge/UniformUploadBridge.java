package dev.fopwoc.julia.backend.bridge;

import com.gtnewhorizons.angelica.glsm.GLStateManager;
import com.gtnewhorizons.angelica.sdlgpu.shader.ShaderManager.ProgramObject;
import dev.fopwoc.julia.backend.BackendInternals;
import dev.fopwoc.julia.backend.shader.SamplerUniforms;
import java.nio.IntBuffer;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.util.spvc.Spvc;

/** Integer-array uploads, with sampler/image scalars using their upstream binding path. */
public final class UniformUploadBridge {
    private UniformUploadBridge() {}

    public static void glUniform1iv(int location, IntBuffer values) {
        GLStateManager.glUniform1(location, values);
    }

    public static void glUniform1iv(int location, int[] values) {
        if (location < 0 || values.length == 0) return;
        if (SamplerUniforms.upload(location, IntBuffer.wrap(values))) return;
        var program = BackendInternals.context().boundProgramObj;
        if (program == null
                || !program.linked
                || location >= program.uniformSlotCount
                || !program.locationToName.containsKey(location)) {
            throw new IllegalArgumentException("Invalid integer uniform location: " + location);
        }
        if (location < program.locationKind.length
                && program.locationKind[location] != ProgramObject.LOCATION_KIND_PLAIN) {
            if (values.length != 1) {
                throw new UnsupportedOperationException("Sampler/image array locations require upstream array support");
            }
            GLStateManager.glUniform1i(location, values[0]);
            return;
        }
        var info = program.anyBlockMemberInfo(location);
        if (info == null) info = program.locationToMemberInfo.get(location);
        boolean integral = info != null
                && (info.baseType() == Spvc.SPVC_BASETYPE_INT32
                        || info.baseType() == Spvc.SPVC_BASETYPE_BOOLEAN
                        || program.boolUniforms.contains(program.locationToName.get(location)));
        if (!integral || info.vectorSize() != 1 || info.columns() != 1) {
            throw new IllegalArgumentException("Expected a signed integer or boolean scalar/array uniform");
        }
        if (values.length > Math.max(1, info.arrayLen())) {
            throw new UnsupportedOperationException("Integer upload exceeds reflected array length");
        }
        var buffer = MemoryUtil.memAllocInt(values.length);
        try {
            buffer.put(values).flip();
            GLStateManager.glUniform1(location, buffer);
        } finally {
            MemoryUtil.memFree(buffer);
        }
    }
}
