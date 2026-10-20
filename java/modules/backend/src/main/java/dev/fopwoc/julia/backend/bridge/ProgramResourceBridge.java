package dev.fopwoc.julia.backend.bridge;

import static org.lwjgl.opengl.GL43.*;

import dev.fopwoc.julia.backend.BackendInternals;
import dev.fopwoc.julia.backend.shader.ProgramResources;
import dev.fopwoc.julia.backend.shader.ResourceOutput;
import dev.fopwoc.julia.backend.shader.UniformResources;
import java.nio.IntBuffer;
import java.util.Objects;

/** Program interface queries use reflected layouts and active stage usage, without native OpenGL access. */
public final class ProgramResourceBridge {
    private ProgramResourceBridge() {}

    public static int glGetProgramInterfacei(int program, int api, int property) {
        if (api == GL_UNIFORM && property == GL_MAX_NAME_LENGTH) {
            var reflected = uniforms(program);
            int maximum = 0;
            for (int i = 0; i < reflected.query(GL_ACTIVE_RESOURCES); i++)
                maximum = Math.max(
                        maximum,
                        BackendInternals.programs()
                                        .publicUniformName(program, reflected.name(i))
                                        .length()
                                + 1);
            return maximum;
        }
        return api == GL_UNIFORM
                ? uniforms(program).query(property)
                : resources(program).query(api, property);
    }

    public static void glGetProgramInterfaceiv(int program, int api, int property, int[] output) {
        if (output.length == 0) throw new IllegalArgumentException("Empty interface query output");
        output[0] = glGetProgramInterfacei(program, api, property);
    }

    public static void glGetProgramInterfaceiv(int program, int api, int property, IntBuffer output) {
        if (!output.hasRemaining()) throw new IllegalArgumentException("Empty interface query output");
        output.put(output.position(), glGetProgramInterfacei(program, api, property));
    }

    public static int glGetProgramResourceIndex(int program, int api, CharSequence name) {
        String text = Objects.requireNonNull(name).toString();
        return api == GL_UNIFORM
                ? uniforms(program).index(BackendInternals.programs().backendUniformName(program, text))
                : resources(program).index(api, text);
    }

    public static String glGetProgramResourceName(int program, int api, int index, int capacity) {
        String name = api == GL_UNIFORM
                ? BackendInternals.programs()
                        .publicUniformName(program, uniforms(program).name(index))
                : resources(program).name(api, index);
        return ResourceOutput.name(name, capacity);
    }

    public static void glGetProgramResourceiv(
            int program, int api, int index, int[] properties, int[] length, int[] output) {
        Objects.requireNonNull(properties);
        ResourceOutput.write(properties(program, api, index, properties), length, output);
    }

    public static void glGetProgramResourceiv(
            int program, int api, int index, IntBuffer properties, IntBuffer length, IntBuffer output) {
        int[] values = new int[properties.remaining()];
        properties.get(properties.position(), values);
        ResourceOutput.write(properties(program, api, index, values), length, output);
    }

    private static int[] properties(int program, int api, int index, int[] properties) {
        int[] values = api == GL_UNIFORM
                ? uniforms(program).properties(index, properties)
                : resources(program).properties(api, index, properties);
        if (api == GL_UNIFORM)
            for (int i = 0; i < properties.length; i++)
                if (properties[i] == GL_NAME_LENGTH)
                    values[i] = BackendInternals.programs()
                                    .publicUniformName(
                                            program, uniforms(program).name(index))
                                    .length()
                            + 1;
        return values;
    }

    private static UniformResources uniforms(int program) {
        return BackendInternals.programResources()
                .uniforms(BackendInternals.shaders().getProgram(program));
    }

    private static ProgramResources resources(int program) {
        return BackendInternals.programResources()
                .get(BackendInternals.shaders().getProgram(program));
    }
}
