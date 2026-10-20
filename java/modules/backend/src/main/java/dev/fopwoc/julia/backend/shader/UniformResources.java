package dev.fopwoc.julia.backend.shader;

import static org.lwjgl.opengl.GL43.*;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;

/** Merges uniform identities across stages while keeping their actual resource count and array lengths. */
public final class UniformResources {
    private final List<UniformVariable> uniforms;

    public UniformResources(List<UniformVariable> stages) {
        var merged = new LinkedHashMap<String, UniformVariable>();
        for (var uniform : stages)
            merged.merge(uniform.name(), uniform, (first, second) -> {
                if (first.type() != second.type() || first.location() != second.location())
                    throw new UnsupportedOperationException("Inconsistent cross-stage uniform: " + first.name());
                return new UniformVariable(
                        first.name(),
                        first.type(),
                        Math.max(first.size(), second.size()),
                        first.location(),
                        first.stages() | second.stages());
            });
        uniforms = merged.values().stream()
                .sorted(Comparator.comparing(UniformVariable::name))
                .toList();
    }

    public int query(int property) {
        return switch (property) {
            case GL_ACTIVE_RESOURCES -> uniforms.size();
            case GL_MAX_NAME_LENGTH ->
                uniforms.stream()
                        .mapToInt(value -> value.name().length() + 1)
                        .max()
                        .orElse(0);
            default -> throw new UnsupportedOperationException("Unsupported uniform interface property: " + property);
        };
    }

    public int index(String name) {
        for (int i = 0; i < uniforms.size(); i++) {
            String candidate = uniforms.get(i).name();
            if (candidate.equals(name)
                    || candidate.endsWith("[0]")
                            && candidate.substring(0, candidate.length() - 3).equals(name)) return i;
        }
        return -1;
    }

    private UniformVariable get(int index) {
        if (index < 0 || index >= uniforms.size())
            throw new IllegalArgumentException("Invalid uniform resource index: " + index);
        return uniforms.get(index);
    }

    public String name(int index) {
        return get(index).name();
    }

    public int[] properties(int index, int[] properties) {
        var value = get(index);
        int[] output = new int[properties.length];
        for (int i = 0; i < properties.length; i++)
            output[i] = switch (properties[i]) {
                case GL_NAME_LENGTH -> value.name().length() + 1;
                case GL_TYPE -> value.type();
                case GL_ARRAY_SIZE -> value.size();
                case GL_LOCATION -> value.location();
                case GL_BLOCK_INDEX, GL_OFFSET, GL_ARRAY_STRIDE, GL_MATRIX_STRIDE, GL_ATOMIC_COUNTER_BUFFER_INDEX -> -1;
                case GL_IS_ROW_MAJOR,
                        GL_REFERENCED_BY_GEOMETRY_SHADER,
                        GL_REFERENCED_BY_TESS_CONTROL_SHADER,
                        GL_REFERENCED_BY_TESS_EVALUATION_SHADER -> 0;
                case GL_REFERENCED_BY_VERTEX_SHADER -> (value.stages() & StorageBlock.VERTEX) != 0 ? 1 : 0;
                case GL_REFERENCED_BY_FRAGMENT_SHADER -> (value.stages() & StorageBlock.FRAGMENT) != 0 ? 1 : 0;
                case GL_REFERENCED_BY_COMPUTE_SHADER -> (value.stages() & StorageBlock.COMPUTE) != 0 ? 1 : 0;
                default ->
                    throw new UnsupportedOperationException("Unsupported uniform resource property: " + properties[i]);
            };
        return output;
    }
}
