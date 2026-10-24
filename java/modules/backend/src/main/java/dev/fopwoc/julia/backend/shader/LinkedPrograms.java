package dev.fopwoc.julia.backend.shader;

import com.gtnewhorizons.angelica.sdlgpu.shader.ShaderManager.ProgramObject;
import java.util.WeakHashMap;

/** One {@link LinkedProgram} per upstream program, replaced whenever the program relinks. */
public final class LinkedPrograms {
    private final WeakHashMap<ProgramObject, LinkedProgram> programs = new WeakHashMap<>();
    // Sampler binding asks for the bound program per slot on every draw; it usually repeats, so skip the weak map.
    private ProgramObject last;
    private LinkedProgram lastLinked;

    public LinkedProgram of(ProgramObject program) {
        if (program == null) throw new IllegalArgumentException("Unknown or unlinked program");
        var linked = program == last ? lastLinked : programs.get(program);
        if (linked == null || !linked.matches(program)) {
            linked = new LinkedProgram(program);
            programs.put(program, linked);
        }
        last = program;
        lastLinked = linked;
        return linked;
    }

    public void clear() {
        programs.clear();
        last = null;
        lastLinked = null;
    }
}
