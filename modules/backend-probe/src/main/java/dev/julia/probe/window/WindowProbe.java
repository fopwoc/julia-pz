package dev.julia.probe.window;

import com.gtnewhorizons.angelica.glsm.GLStateManager;
import com.gtnewhorizons.angelica.glsm.backend.BackendManager;
import com.gtnewhorizons.angelica.glsm.backend.BackendOptions;
import com.gtnewhorizons.angelica.glsm.hooks.GLSMInitConfig;
import com.gtnewhorizons.angelica.sdlgpu.SDLGPUGate;
import com.gtnewhorizons.angelica.sdlgpu.SDLGPURenderBackend;
import dev.julia.probe.adapter.AdapterDraw;
import dev.julia.probe.host.ProbeGlRuntime;
import dev.julia.probe.adapter.HostedBackend;
import me.eigenraven.lwjgl3ify.api.GLCapabilitiesOverride;
import java.nio.file.Files;
import java.nio.file.Path;
import org.lwjgl.sdl.SDLError;
import org.lwjgl.sdl.SDLInit;
import org.lwjgl.system.Configuration;
import org.lwjgl.system.MemoryStack;
import static org.lwjgl.sdl.SDLGPU.*;
import static org.lwjgl.sdl.SDLVideo.*;

/** Three full-backend frames presented into a borrowed GLFW Cocoa window, followed by complete detach. */
public final class WindowProbe {
    private WindowProbe() { }

    public static void run(Path destination, long cocoaWindow) throws Exception {
        Path output = destination.toAbsolutePath();
        Files.createDirectories(output);
        Files.deleteIfExists(output.resolve("result.txt"));
        Files.deleteIfExists(output.resolve("window-texture.png"));
        Configuration.OPENGL_EXPLICIT_INIT.set(true);
        BackendOptions.latch(true, "metal", 0);
        if (!SDLInit.SDL_Init(SDLInit.SDL_INIT_VIDEO)) throw failure("Initialize SDL video");
        String result;
        try (var borrowed = new BorrowedCocoaWindow(cocoaWindow); var stack = MemoryStack.stackPush()) {
            var width = stack.ints(0);
            var height = stack.ints(0);
            if (!SDL_GetWindowSize(borrowed.handle(), width, height)) throw failure("Read logical window dimensions");
            try (var binding = HostWindow.bind(borrowed.handle(), width.get(0), height.get(0))) {
                var device = SDLGPUGate.device();
                boolean backendCreated = false;
                try {
                    if (!device.createDevice() || !device.isMetal()) throw failure("Create Metal device");
                    device.claimWindow(binding.handle());
                    var backend = BackendManager.RENDER_BACKEND;
                    backendCreated = true;
                    if (!(backend instanceof HostedBackend)) throw new IllegalStateException("Unexpected backend: " + backend.getName());
                    backend.onPostWindowCreate(borrowed.handle());
                    GLCapabilitiesOverride.set(SDLGPURenderBackend.advertisedCapabilities());
                    if (!SDL_GetWindowSizeInPixels(borrowed.handle(), width, height)) throw failure("Read window pixel dimensions");
                    int w = width.get(0);
                    int h = height.get(0);
                    if (w <= 0 || h <= 0 || (long) w * h > 16_777_216) throw new IllegalStateException("Unsupported probe size: " + w + "x" + h);
                    GLStateManager.initialize(GLSMInitConfig.builder().displaySize(w, h).build());
                    GLStateManager.setRunningSplash(false);
                    GLStateManager.markSplashComplete("Julia window probe");
                    AdapterDraw.runWindow(output, w, h);
                    if (!SDL_WaitForGPUIdle(device.getDevice())) throw failure("Wait for presented frames");
                    result = "PASS\nbackend=" + backend.getName() + "\nwindow=borrowed GLFW Cocoa window"
                            + "\nframes_presented=3\nsize=" + w + "x" + h + "\nverified_pixels=" + (long) w * h
                            + "\nprocess_id=" + ProcessHandle.current().pid() + "\nthread=" + Thread.currentThread().getName() + "\n";
                } finally {
                    if (backendCreated) BackendManager.shutdown();
                    else device.destroyDevice();
                }
            }
        } finally {
            try { ProbeGlRuntime.close(); }
            finally { SDLInit.SDL_Quit(); }
        }
        Files.writeString(output.resolve("result.txt"), result + "cocoa_state_restored=true\n");
        System.out.println("[Julia] PASS: three Metal frames presented into PZ's GLFW window; Cocoa state restored");
    }

    private static IllegalStateException failure(String operation) {
        return new IllegalStateException(operation + ": " + SDLError.SDL_GetError());
    }
}
