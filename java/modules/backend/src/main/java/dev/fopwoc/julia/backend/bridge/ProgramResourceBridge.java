package dev.fopwoc.julia.backend.bridge;

import dev.fopwoc.julia.backend.BackendInternals;
import dev.fopwoc.julia.backend.shader.ProgramResources;
import dev.fopwoc.julia.backend.shader.ResourceOutput;
import java.util.Objects;

/** Exact storage-block query overloads observed in vanilla; no native GL call or native type crosses the boundary. */
public final class ProgramResourceBridge {
    private ProgramResourceBridge() {}

    public static int glGetProgramResourceIndex(int program, int api, CharSequence name) {
        return resources(program).index(api, Objects.requireNonNull(name).toString());
    }

    public static String glGetProgramResourceName(int program, int api, int index, int capacity) {
        return ResourceOutput.name(resources(program).name(api, index), capacity);
    }

    public static void glGetProgramResourceiv(
            int program, int api, int index, int[] properties, int[] length, int[] output) {
        Objects.requireNonNull(properties);
        ResourceOutput.write(resources(program).properties(api, index, properties), length, output);
    }

    private static ProgramResources resources(int program) {
        return BackendInternals.programResources()
                .get(BackendInternals.shaders().getProgram(program));
    }
}
