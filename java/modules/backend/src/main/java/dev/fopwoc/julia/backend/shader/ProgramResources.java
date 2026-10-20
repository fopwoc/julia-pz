package dev.fopwoc.julia.backend.shader;

import static org.lwjgl.opengl.GL43.*;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

/** A deterministic, merged program interface. Block and member indices are distinct from GL bindings. */
public final class ProgramResources {
    private record Member(ResourceVariable variable, int block) {}

    private final List<StorageBlock> blocks;
    private final List<Member> members;
    private final List<int[]> activeVariables;

    public ProgramResources(List<StorageBlock> stages) {
        Map<String, StorageBlock> merged = new LinkedHashMap<>();
        for (var block : stages)
            merged.merge(block.name(), block, (first, second) -> {
                if (first.binding() != second.binding()
                        || first.bytes() != second.bytes()
                        || !first.variables().equals(second.variables())) {
                    throw new UnsupportedOperationException("Inconsistent cross-stage block layout: " + first.name());
                }
                return new StorageBlock(
                        first.name(),
                        first.binding(),
                        first.bytes(),
                        first.variables(),
                        first.stages() | second.stages());
            });
        blocks = merged.values().stream()
                .sorted(Comparator.comparing(StorageBlock::name))
                .toList();
        var memberList = new ArrayList<Member>();
        var active = new ArrayList<int[]>();
        for (int block = 0; block < blocks.size(); block++) {
            var storage = blocks.get(block);
            var indices = new int[storage.variables().size()];
            for (int i = 0; i < indices.length; i++) {
                indices[i] = memberList.size();
                memberList.add(new Member(storage.variables().get(i), block));
            }
            active.add(indices);
        }
        members = List.copyOf(memberList);
        activeVariables = List.copyOf(active);
    }

    public int index(int api, String name) {
        int count = count(api);
        for (int i = 0; i < count; i++) {
            String candidate = name(api, i);
            if (candidate.equals(name)
                    || candidate.endsWith("[0]")
                            && candidate.substring(0, candidate.length() - 3).equals(name)) return i;
        }
        return -1;
    }

    public int query(int api, int property) {
        int size = count(api);
        return switch (property) {
            case GL_ACTIVE_RESOURCES -> size;
            case GL_MAX_NAME_LENGTH ->
                IntStream.range(0, size)
                        .map(index -> name(api, index).length() + 1)
                        .max()
                        .orElse(0);
            case GL_MAX_NUM_ACTIVE_VARIABLES -> {
                if (api != GL_SHADER_STORAGE_BLOCK)
                    throw new IllegalArgumentException("Active-variable interface query requires blocks");
                yield activeVariables.stream()
                        .mapToInt(values -> values.length)
                        .max()
                        .orElse(0);
            }
            default -> throw new UnsupportedOperationException("Unsupported program interface property: " + property);
        };
    }

    public int count(int api) {
        return switch (api) {
            case GL_SHADER_STORAGE_BLOCK -> blocks.size();
            case GL_BUFFER_VARIABLE -> members.size();
            default -> throw new UnsupportedOperationException("Unsupported program interface: " + api);
        };
    }

    public String name(int api, int index) {
        check(api, index);
        return api == GL_SHADER_STORAGE_BLOCK
                ? blocks.get(index).name()
                : members.get(index).variable().name();
    }

    public int[] properties(int api, int index, int[] properties) {
        check(api, index);
        var result = new ArrayList<Integer>();
        for (int property : properties) {
            if (property == GL_NAME_LENGTH) result.add(name(api, index).length() + 1);
            else if (api == GL_SHADER_STORAGE_BLOCK) {
                var block = blocks.get(index);
                switch (property) {
                    case GL_BUFFER_BINDING -> result.add(block.binding());
                    case GL_BUFFER_DATA_SIZE -> result.add(block.bytes());
                    case GL_NUM_ACTIVE_VARIABLES -> result.add(activeVariables.get(index).length);
                    case GL_ACTIVE_VARIABLES -> {
                        for (int variable : activeVariables.get(index)) result.add(variable);
                    }
                    default -> result.add(reference(property, block.stages()));
                }
            } else {
                var member = members.get(index);
                var variable = member.variable();
                result.add(
                        switch (property) {
                            case GL_TYPE -> variable.type();
                            case GL_ARRAY_SIZE -> variable.arraySize();
                            case GL_OFFSET -> variable.offset();
                            case GL_BLOCK_INDEX -> member.block();
                            case GL_ARRAY_STRIDE -> variable.arrayStride();
                            case GL_MATRIX_STRIDE -> variable.matrixStride();
                            case GL_IS_ROW_MAJOR -> variable.rowMajor() ? 1 : 0;
                            case GL_TOP_LEVEL_ARRAY_SIZE -> variable.topArraySize();
                            case GL_TOP_LEVEL_ARRAY_STRIDE -> variable.topArrayStride();
                            default ->
                                throw new UnsupportedOperationException(
                                        "Unsupported buffer-variable property: " + property);
                        });
            }
        }
        return result.stream().mapToInt(Integer::intValue).toArray();
    }

    private void check(int api, int index) {
        if (index < 0 || index >= count(api))
            throw new IllegalArgumentException("Invalid program resource index: " + index);
    }

    private static int reference(int property, int stages) {
        return switch (property) {
            case GL_REFERENCED_BY_VERTEX_SHADER -> (stages & StorageBlock.VERTEX) != 0 ? 1 : 0;
            case GL_REFERENCED_BY_FRAGMENT_SHADER -> (stages & StorageBlock.FRAGMENT) != 0 ? 1 : 0;
            case GL_REFERENCED_BY_COMPUTE_SHADER -> (stages & StorageBlock.COMPUTE) != 0 ? 1 : 0;
            default -> throw new UnsupportedOperationException("Unsupported program resource property: " + property);
        };
    }
}
