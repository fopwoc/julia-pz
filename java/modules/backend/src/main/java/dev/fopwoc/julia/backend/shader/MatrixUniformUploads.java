package dev.fopwoc.julia.backend.shader;

import com.gtnewhorizons.angelica.sdlgpu.shader.ShaderManager.ProgramObject;
import java.nio.FloatBuffer;
import org.lwjgl.util.spvc.Spvc;

/** Validates before upstream staging is mutated, so invalid GL uploads cannot overflow the uniform buffer. */
public final class MatrixUniformUploads {
    private MatrixUniformUploads() {}

    public static FloatBuffer prepare(ProgramObject program, int location, int size, FloatBuffer source) {
        if (program == null || !program.linked || !program.locationToName.containsKey(location))
            throw new IllegalArgumentException("No linked program with this uniform location");
        var member = program.anyBlockMemberInfo(location);
        if (member == null) member = program.locationToMemberInfo.get(location);
        if (member == null
                || member.baseType() != Spvc.SPVC_BASETYPE_FP32
                || member.vectorSize() != size
                || member.columns() != size)
            throw new IllegalArgumentException("Uniform is not a matching float matrix");
        int count = source.remaining() / (size * size);
        if (count == 0) return null;
        boolean array = member.arrayStride() > 0 || member.arrayLen() > 1;
        if (count > 1 && !array)
            throw new IllegalArgumentException("Multiple matrices supplied for a non-array uniform");
        // GL ignores writes past the end of an array; incomplete trailing matrices are ignored too.
        int written = Math.min(count, Math.max(1, member.arrayLen())) * size * size;
        return source.duplicate().limit(source.position() + written);
    }
}
