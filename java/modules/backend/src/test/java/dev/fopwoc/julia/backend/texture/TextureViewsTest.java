package dev.fopwoc.julia.backend.texture;

import static org.junit.jupiter.api.Assertions.*;
import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL12.*;
import static org.lwjgl.opengl.GL30.*;

import com.gtnewhorizons.angelica.sdlgpu.resource.ResourceManager;
import org.junit.jupiter.api.Test;

class TextureViewsTest {
    @Test
    void composesNestedOffsetsAndClampsCountsWithoutLosingImmutableLevels() {
        var meta = new ResourceManager.TextureMeta(GL_TEXTURE_2D_ARRAY, GL_RGBA8, 0, 32, 16, 6, 5, 0);
        var first = TextureViewRange.storage(meta).subset(meta, GL_TEXTURE_2D_ARRAY, GL_RGBA8, 1, 99, 2, 3);
        assertEquals(new TextureViewRange(1, 4, 2, 3, 5), first);
        var childMeta = new ResourceManager.TextureMeta(GL_TEXTURE_2D_ARRAY, GL_RGBA8, 0, 16, 8, 3, 4, 0);
        assertEquals(
                new TextureViewRange(3, 2, 3, 1, 5), first.subset(childMeta, GL_TEXTURE_2D, GL_RGBA8, 2, 99, 1, 1));
    }

    @Test
    void rejectsInvalidRangesAndUnsupportedFormatOrTargetChanges() {
        var meta = new ResourceManager.TextureMeta(GL_TEXTURE_2D, GL_RGBA8, 0, 32, 16, 1, 3, 0);
        assertDoesNotThrow(() -> TextureViews.validate(meta, GL_TEXTURE_2D, GL_RGBA8, 1, 2, 0, 1));
        for (int[] range : new int[][] {{-1, 1, 0, 1}, {3, 1, 0, 1}, {0, 0, 0, 1}, {0, 1, 1, 1}, {0, 1, 0, 2}})
            assertThrows(
                    IllegalArgumentException.class,
                    () -> TextureViews.validate(meta, GL_TEXTURE_2D, GL_RGBA8, range[0], range[1], range[2], range[3]));
        assertThrows(
                UnsupportedOperationException.class,
                () -> TextureViews.validate(meta, GL_TEXTURE_2D, GL_RGB8, 0, 3, 0, 1));
        assertThrows(
                UnsupportedOperationException.class,
                () -> TextureViews.validate(meta, GL_TEXTURE_3D, GL_RGBA8, 0, 3, 0, 1));
    }
}
