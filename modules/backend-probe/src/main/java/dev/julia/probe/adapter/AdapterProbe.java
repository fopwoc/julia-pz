package dev.julia.probe.adapter;

import com.gtnewhorizons.angelica.glsm.GLStateManager;
import com.gtnewhorizons.angelica.glsm.backend.BackendManager;
import com.gtnewhorizons.angelica.glsm.backend.BackendOptions;
import com.gtnewhorizons.angelica.glsm.hooks.GLSMInitConfig;
import com.gtnewhorizons.angelica.sdlgpu.SDLGPUGate;
import com.gtnewhorizons.angelica.sdlgpu.SDLGPURenderBackend;
import me.eigenraven.lwjgl3ify.api.GLCapabilitiesOverride;
import dev.julia.probe.host.ProbeGlRuntime;
import java.nio.file.Files;
import java.nio.file.Path;
import org.lwjgl.sdl.SDLInit;
import org.lwjgl.system.Configuration;

/** First full graphics-adapter startup, kept separate from the raw-device probe. */
public final class AdapterProbe {
    private AdapterProbe() { }

    public static void run(Path destination) throws Exception {
        int width = Integer.getInteger("julia.probe.width", 32);
        int height = Integer.getInteger("julia.probe.height", 32);
        if (width <= 0 || height <= 0 || (long) width * height > 16_777_216) {
            throw new IllegalArgumentException("Unsupported offscreen probe size: " + width + "x" + height);
        }
        Path output = destination.toAbsolutePath();
        Files.createDirectories(output);
        Files.deleteIfExists(output.resolve("result.txt"));
        Files.deleteIfExists(output.resolve("adapter-texture.png"));
        if (Configuration.SHARED_LIBRARY_EXTRACT_PATH.get() == null) {
            Configuration.SHARED_LIBRARY_EXTRACT_PATH.set(output.resolve("natives").toString());
        }
        Configuration.OPENGL_EXPLICIT_INIT.set(true);
        BackendOptions.latch(true, "metal", 0);
        if (!SDLInit.SDL_Init(SDLInit.SDL_INIT_VIDEO)) throw new IllegalStateException("SDL video initialization failed");
        var device = SDLGPUGate.device();
        boolean backendCreated = false;
        String result;
        try {
            if (!device.createDevice() || !device.isMetal()) throw new IllegalStateException("Metal device unavailable");
            var backend = BackendManager.RENDER_BACKEND;
            backendCreated = true;
            if (!(backend instanceof HostedBackend)) throw new IllegalStateException("Wrong backend: " + backend.getName());
            backend.onPostWindowCreate(0);
            GLCapabilitiesOverride.set(SDLGPURenderBackend.advertisedCapabilities());
            GLStateManager.initialize(GLSMInitConfig.builder().displaySize(width, height).build());
            GLStateManager.setRunningSplash(false);
            GLStateManager.markSplashComplete("Julia adapter probe");
            AdapterDraw.run(output, width, height);
            result = "PASS\nbackend=" + backend.getName()
                    + "\nsize=" + width + "x" + height + "\nframes_drawn=3\nverified_pixels=" + (long) width * height + "\napi=redirected LWJGL OpenGL calls -> GLStateManager"
                    + "\ndraw=sampled texture\nuniform_checks=float array,matrix array transpose,truncated reflection,buffer positions\nprocess_id=" + ProcessHandle.current().pid()
                    + "\nthread=" + Thread.currentThread().getName() + "\n";
        } finally {
            try {
                if (backendCreated) BackendManager.shutdown();
                else device.destroyDevice();
            } finally {
                try { ProbeGlRuntime.close(); }
                finally { SDLInit.SDL_Quit(); }
            }
        }
        Files.writeString(output.resolve("result.txt"), result);
        System.out.println("[Julia] PASS: full GLSM / SDL GPU adapter initialized; " + (long) width * height + " textured pixels verified");
    }
}
