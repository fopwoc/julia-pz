package dev.fopwoc.julia.backend.bridge;

import com.gtnewhorizons.angelica.glsm.GLStateManager;
import com.gtnewhorizons.angelica.sdlgpu.shader.ShaderManager.ProgramObject;
import dev.fopwoc.julia.backend.Upstream;
import dev.fopwoc.julia.backend.shader.SamplerUniforms;
import java.nio.IntBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.util.spvc.Spvc;

/** Integer-array uploads, with sampler/image scalars using their upstream binding path. */
public final class UniformUploadBridge {
    private UniformUploadBridge() {}

    public static void glUniform1iv(int location, IntBuffer values) {
        GLStateManager.glUniform1(location, values);
    }

    // Upstream stores plain integer uniforms by bit pattern, so unsigned values ride the signed path once the
    // reflected type confirms the location really is unsigned.
    public static void glUniform1ui(int location, int x) {
        if (unsigned(location, 1)) GLStateManager.glUniform1i(location, x);
    }

    public static void glUniform2ui(int location, int x, int y) {
        if (unsigned(location, 2)) GLStateManager.glUniform2i(location, x, y);
    }

    public static void glUniform3ui(int location, int x, int y, int z) {
        if (unsigned(location, 3)) GLStateManager.glUniform3i(location, x, y, z);
    }

    public static void glUniform4ui(int location, int x, int y, int z, int w) {
        if (unsigned(location, 4)) GLStateManager.glUniform4i(location, x, y, z, w);
    }

    private static boolean unsigned(int location, int components) {
        if (location == -1) return false;
        var program = Upstream.context().boundProgramObj;
        if (program == null
                || !program.linked
                || location < 0
                || location >= program.uniformSlotCount
                || !program.locationToName.containsKey(location))
            throw new IllegalArgumentException("Invalid unsigned uniform location: " + location);
        var info = program.anyBlockMemberInfo(location);
        if (info == null) info = program.locationToMemberInfo.get(location);
        if (info == null
                || info.baseType() != Spvc.SPVC_BASETYPE_UINT32
                || info.vectorSize() != components
                || info.columns() != 1)
            throw new IllegalArgumentException("Uniform is not an unsigned " + components + "-component vector");
        return true;
    }

    public static void glUniform1iv(int location, int[] values) {
        if (location < 0 || values.length == 0) return;
        if (SamplerUniforms.upload(location, IntBuffer.wrap(values))) return;
        var program = Upstream.context().boundProgramObj;
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
        // Upstream copies uniform data before returning, so reflected-length arrays fit the thread stack.
        try (var stack = MemoryStack.stackPush()) {
            GLStateManager.glUniform1(location, stack.ints(values));
        }
    }
}
