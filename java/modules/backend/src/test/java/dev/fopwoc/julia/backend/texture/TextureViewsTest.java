package dev.fopwoc.julia.backend.texture;

import static org.junit.jupiter.api.Assertions.*;
import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL12.*;

import com.gtnewhorizons.angelica.sdlgpu.resource.ResourceManager;
import org.junit.jupiter.api.Test;

class TextureViewsTest {
    @Test
    void rejectsSubsetsAndFormatOrTargetChangesInsteadOfCopyingStorage() {
        var meta = new ResourceManager.TextureMeta(GL_TEXTURE_2D, GL_RGBA8, 0, 32, 16, 1, 3, 0);
        assertDoesNotThrow(() -> TextureViews.validate(meta, GL_TEXTURE_2D, GL_RGBA8, 0, 3, 0, 1));
        assertThrows(
                UnsupportedOperationException.class,
                () -> TextureViews.validate(meta, GL_TEXTURE_2D, GL_RGBA8, 1, 2, 0, 1));
        assertThrows(
                UnsupportedOperationException.class,
                () -> TextureViews.validate(meta, GL_TEXTURE_2D, GL_RGBA8, 0, 3, 1, 1));
        assertThrows(
                UnsupportedOperationException.class,
                () -> TextureViews.validate(meta, GL_TEXTURE_2D, GL_RGB8, 0, 3, 0, 1));
        assertThrows(
                UnsupportedOperationException.class,
                () -> TextureViews.validate(meta, GL_TEXTURE_3D, GL_RGBA8, 0, 3, 0, 1));
    }
}
