package dev.fopwoc.julia.probe.scene;

import static org.lwjgl.opengl.GL11.GL_NO_ERROR;
import static org.lwjgl.opengl.GL11.glGetError;

import com.gtnewhorizons.angelica.glsm.backend.BackendManager;
import java.io.IOException;
import java.nio.file.Path;

/** Focused GPU query checks through the same redirected GL calls as the complete adapter suite. */
public final class QueryScene {
    private QueryScene() {}

    public static void run(Path output) throws IOException {
        var backend = BackendManager.RENDER_BACKEND;
        backend.onFrameBegin();
        try {
            TimerQueryChecks.run(output);
            OcclusionQueryChecks.run(output);
            int error = glGetError();
            if (error != GL_NO_ERROR) throw new IllegalStateException("Query probe GL error: " + error);
        } finally {
            backend.onFrameEnd();
        }
    }
}
