package dev.fopwoc.julia.backend.shader;

import com.gtnewhorizons.angelica.sdlgpu.shader.ShaderManager;
import java.nio.ByteBuffer;
import java.util.Map;
import java.util.WeakHashMap;

/** Owned by one hosted backend; weak program keys and linked-buffer identity prevent stale relink results. */
public final class ProgramResourceCache {
    private record Entry(ByteBuffer vertex, ByteBuffer fragment, ByteBuffer compute, ProgramResources resources) {
        boolean matches(ShaderManager.ProgramObject program) {
            return vertex == program.vertexSpirv
                    && fragment == program.fragmentSpirv
                    && compute == program.computeSpirv;
        }
    }

    private final Map<ShaderManager.ProgramObject, Entry> entries = new WeakHashMap<>();

    public ProgramResources get(ShaderManager.ProgramObject program) {
        if (program == null || !program.linked) throw new IllegalArgumentException("Unknown or unlinked program");
        if (program.geometrySpirv != null)
            throw new UnsupportedOperationException("Geometry-stage resource introspection");
        var entry = entries.get(program);
        if (entry == null || !entry.matches(program)) {
            entry = new Entry(
                    program.vertexSpirv,
                    program.fragmentSpirv,
                    program.computeSpirv,
                    new ProgramResources(SpirvResources.read(program)));
            entries.put(program, entry);
        }
        return entry.resources();
    }

    public void clear() {
        entries.clear();
    }
}
