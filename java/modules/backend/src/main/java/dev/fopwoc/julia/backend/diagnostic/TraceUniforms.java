package dev.fopwoc.julia.backend.diagnostic;

import static org.lwjgl.util.spvc.Spvc.*;

import com.gtnewhorizons.angelica.sdlgpu.shader.ShaderManager.UniformMemberInfo;
import java.nio.FloatBuffer;

/** Formats logical uniform values and their packed native bytes using reflected scalar types. */
final class TraceUniforms {
    private TraceUniforms() {}

    static String values(float[] data, UniformMemberInfo member) {
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

    static String packed(FloatBuffer buffer, UniformMemberInfo[] members, int location) {
        if (buffer == null || location >= members.length || members[location] == null) return "-";
        var member = members[location];
        int count = Math.min(member.vectorSize(), buffer.capacity() - member.offset() / 4);
        var data = new float[Math.max(0, count)];
        for (int i = 0; i < data.length; i++) data[i] = buffer.get(member.offset() / 4 + i);
        return values(data, member) + "@" + member.offset();
    }
}
