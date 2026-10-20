package dev.fopwoc.julia.backend.shader;

import com.gtnewhorizons.angelica.glsm.backend.BackendManager;
import com.gtnewhorizons.angelica.sdlgpu.shader.ShaderManager.ProgramObject;
import dev.fopwoc.julia.backend.BackendInternals;
import java.nio.IntBuffer;

/** Validate an entire sampler-array assignment before changing any texture-unit mapping. */
public final class SamplerUniforms {
    private SamplerUniforms() {}

    public static boolean upload(int location, IntBuffer values) {
        if (location < 0 || !values.hasRemaining()) return false;
        var program = BackendInternals.context().boundProgramObj;
        if (program == null
                || location >= program.locationKind.length
                || program.locationKind[location] != ProgramObject.LOCATION_KIND_SAMPLER) return false;
        String name = program.locationToName.get(location);
        int bracket = name.lastIndexOf('[');
        String base = bracket < 0 ? name : name.substring(0, bracket);
        int first = bracket < 0 ? 0 : Integer.parseInt(name.substring(bracket + 1, name.length() - 1));
        int[] locations = new int[values.remaining()];
        for (int i = 0; i < locations.length; i++) {
            String element = bracket < 0 ? (i == 0 ? name : "") : base + "[" + (first + i) + "]";
            int at = program.nameToLocation.getInt(element);
            int unit = values.get(values.position() + i);
            if (at < 0 || !program.allSamplerNames.contains(element))
                throw new IllegalArgumentException("Sampler upload exceeds reflected array: " + name);
            if (unit < 0 || unit >= SamplerResources.LIMIT)
                throw new IllegalArgumentException("Sampler texture unit outside 0..31: " + unit);
            locations[i] = at;
        }
        var backend = BackendManager.RENDER_BACKEND;
        for (int i = 0; i < locations.length; i++) backend.uniform1i(locations[i], values.get(values.position() + i));
        return true;
    }
}
