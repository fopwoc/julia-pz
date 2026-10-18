package dev.fopwoc.julia.backend.shader;

import static org.junit.jupiter.api.Assertions.*;
import static org.lwjgl.opengl.GL43.*;

import java.util.List;
import org.junit.jupiter.api.Test;

class ProgramResourcesTest {
    private static ResourceVariable variable(String name, int offset) {
        return new ResourceVariable(name, GL_FLOAT, 2, offset, 4, 0, false, 3, 64);
    }

    @Test
    void mergesStagesAndKeepsResourceIndicesSeparateFromBindings() {
        var z = new StorageBlock("Z", 9, 128, List.of(variable("z[0]", 8)), StorageBlock.VERTEX);
        var a = new StorageBlock("A", 3, 64, List.of(variable("a[0]", 0)), StorageBlock.FRAGMENT);
        var zFragment = new StorageBlock("Z", 9, 128, z.variables(), StorageBlock.FRAGMENT);
        var resources = new ProgramResources(List.of(z, a, zFragment));
        assertEquals(2, resources.count(GL_SHADER_STORAGE_BLOCK));
        assertEquals(1, resources.index(GL_SHADER_STORAGE_BLOCK, "Z"));
        assertEquals(1, resources.index(GL_BUFFER_VARIABLE, "z"));
        assertEquals(-1, resources.index(GL_BUFFER_VARIABLE, "absent"));
        assertArrayEquals(new int[] {9, 128, 1, 1, 1, 0}, resources.properties(GL_SHADER_STORAGE_BLOCK, 1, new int[] {
            GL_BUFFER_BINDING,
            GL_BUFFER_DATA_SIZE,
            GL_NUM_ACTIVE_VARIABLES,
            GL_REFERENCED_BY_VERTEX_SHADER,
            GL_REFERENCED_BY_FRAGMENT_SHADER,
            GL_REFERENCED_BY_COMPUTE_SHADER
        }));
        assertArrayEquals(
                new int[] {1, 9},
                resources.properties(GL_SHADER_STORAGE_BLOCK, 1, new int[] {GL_ACTIVE_VARIABLES, GL_BUFFER_BINDING}));
        assertArrayEquals(new int[] {1, 8, 2, 4, 3, 64}, resources.properties(GL_BUFFER_VARIABLE, 1, new int[] {
            GL_BLOCK_INDEX,
            GL_OFFSET,
            GL_ARRAY_SIZE,
            GL_ARRAY_STRIDE,
            GL_TOP_LEVEL_ARRAY_SIZE,
            GL_TOP_LEVEL_ARRAY_STRIDE
        }));
    }

    @Test
    void rejectsConflictingCrossStageLayouts() {
        var a = new StorageBlock("Shared", 3, 64, List.of(variable("a", 0)), StorageBlock.VERTEX);
        var b = new StorageBlock("Shared", 3, 64, List.of(variable("a", 4)), StorageBlock.FRAGMENT);
        assertThrows(UnsupportedOperationException.class, () -> new ProgramResources(List.of(a, b)));
    }

    @Test
    void rejectsUnknownInterfacesPropertiesAndIndices() {
        var resources = new ProgramResources(
                List.of(new StorageBlock("A", 3, 64, List.of(variable("a", 0)), StorageBlock.VERTEX)));
        assertThrows(UnsupportedOperationException.class, () -> resources.count(GL_UNIFORM));
        assertThrows(
                UnsupportedOperationException.class,
                () -> resources.properties(GL_BUFFER_VARIABLE, 0, new int[] {GL_LOCATION}));
        assertThrows(
                UnsupportedOperationException.class,
                () -> resources.properties(GL_SHADER_STORAGE_BLOCK, 0, new int[] {GL_OFFSET}));
        assertThrows(IllegalArgumentException.class, () -> resources.name(GL_BUFFER_VARIABLE, -1));
        assertThrows(IllegalArgumentException.class, () -> resources.name(GL_SHADER_STORAGE_BLOCK, 1));
    }
}
