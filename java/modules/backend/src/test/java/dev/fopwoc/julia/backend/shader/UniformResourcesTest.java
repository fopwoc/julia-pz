package dev.fopwoc.julia.backend.shader;

import static org.junit.jupiter.api.Assertions.*;
import static org.lwjgl.opengl.GL20.*;
import static org.lwjgl.opengl.GL43.*;

import java.util.List;
import org.junit.jupiter.api.Test;

class UniformResourcesTest {
    @Test
    void mergesArrayUsageAcrossStagesWithoutExpandingResourcesIntoElements() {
        var resources = new UniformResources(List.of(
                new UniformVariable("images[0]", GL_SAMPLER_2D, 4, 7, StorageBlock.VERTEX),
                new UniformVariable("images[0]", GL_SAMPLER_2D, 16, 7, StorageBlock.FRAGMENT)));
        assertEquals(1, resources.query(GL_ACTIVE_RESOURCES));
        assertEquals(10, resources.query(GL_MAX_NAME_LENGTH));
        assertEquals(0, resources.index("images"));
        assertArrayEquals(new int[] {GL_SAMPLER_2D, 16, 1, 1, 0}, resources.properties(0, new int[] {
            GL_TYPE,
            GL_ARRAY_SIZE,
            GL_REFERENCED_BY_VERTEX_SHADER,
            GL_REFERENCED_BY_FRAGMENT_SHADER,
            GL_REFERENCED_BY_COMPUTE_SHADER
        }));
    }

    @Test
    void validatesUnknownPropertiesIndicesAndCrossStageTypes() {
        var resources = new UniformResources(List.of());
        assertEquals(0, resources.query(GL_MAX_NAME_LENGTH));
        assertEquals(-1, resources.index("absent"));
        assertThrows(IllegalArgumentException.class, () -> resources.name(0));
        assertThrows(UnsupportedOperationException.class, () -> resources.query(GL_MAX_NUM_ACTIVE_VARIABLES));
        assertThrows(
                UnsupportedOperationException.class,
                () -> new UniformResources(List.of(
                        new UniformVariable("value", GL_FLOAT, 1, 0, StorageBlock.VERTEX),
                        new UniformVariable("value", GL_INT, 1, 0, StorageBlock.FRAGMENT))));
    }
}
