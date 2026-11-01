package dev.fopwoc.julia.probe.scene;

import static org.lwjgl.opengl.GL11.GL_NO_ERROR;
import static org.lwjgl.opengl.GL11.glGetError;

import com.gtnewhorizons.angelica.glsm.backend.BackendManager;
import java.io.IOException;
import java.nio.file.Path;

/** Sampling regressions isolated from unrelated buffer and draw API checks. */
public final class SamplingScene {
    private SamplingScene() {}

    public static void run(Path output) throws IOException {
        var backend = BackendManager.RENDER_BACKEND;
        backend.onFrameBegin();
        try {
            DefaultTargetChecks.run(output);
            LargeUniformChecks.run(output);
            FloatTextureChecks.run(output);
            TextureMipChecks.run(output);
            VolumeMipChecks.run(output);
            SamplerTargetChecks.run(output);
            BakedMipChecks.run(output);
            PerFragmentSamplerChecks.run(output);
            ManySamplerChecks.run(output);
            FragmentDiscardChecks.run(output);
            TextureCaptureChecks.run(output);
            int error = glGetError();
            if (error != GL_NO_ERROR) throw new IllegalStateException("Sampling probe GL error: " + error);
        } finally {
            backend.onFrameEnd();
        }
    }
}
