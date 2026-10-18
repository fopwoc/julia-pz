package dev.fopwoc.julia.probe.adapter;

import dev.fopwoc.julia.backend.runtime.GlsmSession;
import dev.fopwoc.julia.backend.runtime.HostedVideo;
import dev.fopwoc.julia.probe.scene.AdapterScene;
import dev.fopwoc.julia.probe.scene.ResourceChecks;
import java.nio.file.Files;
import java.nio.file.Path;
import org.lwjgl.system.Configuration;

/** Full GLSM startup over the hosted backend, drawing the redirected scene offscreen. */
public final class AdapterProbe {
    private static final System.Logger LOG = System.getLogger(AdapterProbe.class.getName());

    private AdapterProbe() {}

    public static void run(Path destination) throws Exception {
        var size = new GlsmSession.Size(
                Integer.getInteger("julia.probe.width", 32), Integer.getInteger("julia.probe.height", 32));
        Path output = destination.toAbsolutePath();
        Files.createDirectories(output);
        Files.deleteIfExists(output.resolve("result.txt"));
        Files.deleteIfExists(output.resolve("adapter-texture.png"));
        if (Configuration.SHARED_LIBRARY_EXTRACT_PATH.get() == null) {
            Configuration.SHARED_LIBRARY_EXTRACT_PATH.set(
                    output.resolve("natives").toString());
        }
        String backend;
        try (var video = HostedVideo.start();
                var session = GlsmSession.open(video, 0, () -> size, "Julia adapter probe")) {
            backend = session.backend().getName();
            AdapterScene.offscreen(output, size.width(), size.height());
            DebugMessages.armShutdownCheck();
        }
        DebugMessages.verifyShutdown();
        Files.writeString(output.resolve("result.txt"), """
                PASS
                backend=%s
                size=%dx%d
                frames_drawn=%d
                verified_pixels=%d
                api=LWJGL -> typed RenderDispatch -> isolated GLStateManager
                verified_resource_pixels=%d
                capability_checks=backend snapshot,absent vendor flag
                view_checks=shared writes,nested views,parent deletion,independent state,256 stencil values,view FBO writes
                verified_view_pixels=1152
                verified_program_resource_pixels=64
                program_resource_checks=block layout,struct arrays,matrix stride,name/property truncation,cross-stage merge,original bindings,relink invalidation,compute RO/RW bindings,row-major matrix,runtime array
                debug_checks=registration,UTF8 relay delivery,replacement,unregistration,shutdown cleanup
                alias_checks=depth conversion,vec2 buffer position,image copy
                draw=sampled texture
                uniform_checks=float array,matrix array transpose,truncated reflection,buffer positions
                process_id=%d
                thread=%s
                """.formatted(
                        backend,
                        size.width(),
                        size.height(),
                        AdapterScene.FRAMES,
                        size.pixels(),
                        ResourceChecks.VERIFIED_PIXELS,
                        ProcessHandle.current().pid(),
                        Thread.currentThread().getName()));
        System.out.println("[Julia] PASS: full GLSM / SDL GPU adapter initialized; " + size.pixels()
                + " textured pixels verified");
        LOG.log(System.Logger.Level.INFO, "Adapter probe passed at {0}x{1}", size.width(), size.height());
    }
}
