package dev.fopwoc.julia.backend.shader;

import com.gtnewhorizons.angelica.sdlgpu.shader.ShaderManager.ProgramObject;
import dev.fopwoc.julia.backend.texture.SamplerLayout;
import java.nio.ByteBuffer;

/**
 * Julia's reflection of one linked upstream executable, built lazily. It never references its program, so the weak
 * table that owns it can drop both together.
 */
public final class LinkedProgram {
    private final ByteBuffer vertex, fragment, compute;
    private SamplerLayout samplers;
    private ProgramResources resources;
    private UniformResources uniforms;

    LinkedProgram(ProgramObject program) {
        vertex = program.vertexSpirv;
        fragment = program.fragmentSpirv;
        compute = program.computeSpirv;
    }

    /** Relinking replaces the SPIR-V buffers, so buffer identity tells whether this reflection is still current. */
    boolean matches(ProgramObject program) {
        return vertex == program.vertexSpirv && fragment == program.fragmentSpirv && compute == program.computeSpirv;
    }

    public SamplerLayout samplers(ProgramObject program) {
        if (samplers == null) samplers = new SamplerLayout(program);
        return samplers;
    }

    public ProgramResources resources(ProgramObject program) {
        requireIntrospectable(program);
        if (resources == null) resources = new ProgramResources(SpirvResources.read(program));
        return resources;
    }

    public UniformResources uniforms(ProgramObject program) {
        requireIntrospectable(program);
        if (uniforms == null) uniforms = UniformReflection.read(program);
        return uniforms;
    }

    private static void requireIntrospectable(ProgramObject program) {
        if (!program.linked) throw new IllegalArgumentException("Unknown or unlinked program");
        if (program.geometrySpirv != null)
            throw new UnsupportedOperationException("Geometry-stage resource introspection");
    }
}
