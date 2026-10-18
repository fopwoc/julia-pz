package dev.julia.probe.adapter;

import com.gtnewhorizons.angelica.sdlgpu.shader.ShaderManager;
import com.gtnewhorizons.angelica.sdlgpu.shader.UniformStaging;
import org.lwjgl.util.spvc.Spvc;

/** Reads reflected type information and real staged values, without mirroring shader uploads. */
final class UniformState {
    private UniformState() { }

    static UniformValue read(ShaderManager.ProgramObject program, UniformStaging staging, int location) {
        if (!program.linked || location < 0 || location >= program.uniformSlotCount || !program.locationToName.containsKey(location)) {
            throw new IllegalArgumentException("Invalid linked-program uniform location: " + location);
        }
        byte kind = location < program.locationKind.length ? program.locationKind[location] : ShaderManager.ProgramObject.LOCATION_KIND_PLAIN;
        if (kind == ShaderManager.ProgramObject.LOCATION_KIND_SAMPLER) {
            return integer(program.locationSamplerUnit[location]);
        }
        String name = program.locationToName.get(location);
        if (kind == ShaderManager.ProgramObject.LOCATION_KIND_IMAGE) {
            return integer(program.imageTextureUnits.containsKey(name) ? program.imageTextureUnits.getInt(name) : 0);
        }
        var info = program.anyBlockMemberInfo(location);
        if (info == null) info = program.locationToMemberInfo.get(location);
        if (info == null) throw new UnsupportedOperationException("Uniform has no reflected numeric type: " + name);
        var type = program.boolUniforms.contains(name) ? UniformValue.Kind.BOOL : switch (info.baseType()) {
            case Spvc.SPVC_BASETYPE_FP32 -> UniformValue.Kind.FLOAT;
            case Spvc.SPVC_BASETYPE_INT32 -> UniformValue.Kind.INT;
            case Spvc.SPVC_BASETYPE_UINT32 -> UniformValue.Kind.UINT;
            case Spvc.SPVC_BASETYPE_BOOLEAN -> UniformValue.Kind.BOOL;
            default -> throw new UnsupportedOperationException("Uniform numeric base type: " + info.baseType());
        };
        int components = Math.multiplyExact(info.vectorSize(), info.columns());
        if (components < 1 || components > 16) throw new UnsupportedOperationException("Uniform component count: " + components);
        float[] source = staging.uniformDataBySlot[location];
        var values = new float[components];
        if (source != null) {
            if (source.length < components) throw new IllegalStateException("Incomplete tracked upload for " + name);
            System.arraycopy(source, 0, values, 0, components);
        }
        // An array query returns one element, not the whole staged array.
        return new UniformValue(type, values);
    }

    private static UniformValue integer(int value) {
        return new UniformValue(UniformValue.Kind.INT, new float[] {Float.intBitsToFloat(value)});
    }
}
