package dev.fopwoc.julia.probe.adapter;

import com.gtnewhorizons.angelica.sdlgpu.Subsystems;
import com.gtnewhorizons.angelica.sdlgpu.diagnostic.GpuFaultLog;
import dev.fopwoc.julia.backend.runtime.GlsmSession;
import dev.fopwoc.julia.backend.runtime.HostedVideo;
import dev.fopwoc.julia.probe.scene.TextureScene;
import java.nio.file.Files;
import java.nio.file.Path;
import org.lwjgl.system.Configuration;

/** Focused translated texture storage, transfer and sampling checks, runnable before a backend has every GL extension. */
public final class TextureProbe {
    private TextureProbe() {}

    public static void run(Path output) throws Exception {
        Files.createDirectories(output);
        GpuFaultLog.configure(output);
        Files.deleteIfExists(output.resolve("result.txt"));
        if (Configuration.SHARED_LIBRARY_EXTRACT_PATH.get() == null)
            Configuration.SHARED_LIBRARY_EXTRACT_PATH.set(
                    output.resolve("natives").toAbsolutePath().toString());
        String backend;
        try (var video = HostedVideo.start();
                var session = GlsmSession.open(video, 0, () -> new GlsmSession.Size(32, 32), "Julia texture probe")) {
            backend = session.backend().getName();
            Subsystems.current().drawTrace().open(output);
            TextureScene.run(output);
            DebugMessages.armShutdownCheck();
        }
        DebugMessages.verifyShutdown();
        Files.writeString(
                output.resolve("result.txt"),
                "PASS\nbackend=" + backend
                        + "\nchecks=RGB and floating-point conversion,named uploads,unsigned texture names,typed incomplete samplers,native client buffers,pixel-buffer row layout,shared texture views,nested array subresources,view lifetime\n");
    }
}
