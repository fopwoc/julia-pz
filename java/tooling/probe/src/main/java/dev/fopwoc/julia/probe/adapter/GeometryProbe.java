package dev.fopwoc.julia.probe.adapter;

import dev.fopwoc.julia.backend.runtime.GlsmSession;
import dev.fopwoc.julia.backend.runtime.HostedVideo;
import dev.fopwoc.julia.probe.scene.GeometryScene;
import java.nio.file.Files;
import java.nio.file.Path;
import org.lwjgl.system.Configuration;

/** Focused geometry emission and assembly checks independent of unrelated GL feature coverage. */
public final class GeometryProbe {
    private GeometryProbe() {}

    public static void run(Path output) throws Exception {
        Files.createDirectories(output);
        Files.deleteIfExists(output.resolve("result.txt"));
        if (Configuration.SHARED_LIBRARY_EXTRACT_PATH.get() == null)
            Configuration.SHARED_LIBRARY_EXTRACT_PATH.set(
                    output.resolve("natives").toAbsolutePath().toString());
        String backend;
        try (var video = HostedVideo.start();
                var session = GlsmSession.open(
                        video, 0, () -> new GlsmSession.Size(32, 32), "Julia geometry compute probe")) {
            backend = session.backend().getName();
            GeometryScene.run(output);
            DebugMessages.armShutdownCheck();
        }
        DebugMessages.verifyShutdown();
        Files.writeString(
                output.resolve("result.txt"),
                "PASS\nbackend=" + backend
                        + "\nchecks=GPU geometry emission,six invocations,strip assembly,raster replay,routing discard,overflow\n");
    }
}
