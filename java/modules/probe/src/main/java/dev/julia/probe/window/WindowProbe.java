package dev.julia.probe.window;

import static org.lwjgl.sdl.SDLGPU.SDL_WaitForGPUIdle;
import static org.lwjgl.sdl.SDLVideo.SDL_GetWindowSize;
import static org.lwjgl.sdl.SDLVideo.SDL_GetWindowSizeInPixels;

import dev.julia.backend.runtime.GlsmSession;
import dev.julia.backend.runtime.HostedVideo;
import dev.julia.backend.utils.Sdl;
import dev.julia.backend.window.BorrowedWindow;
import dev.julia.backend.window.HostWindow;
import dev.julia.probe.scene.AdapterScene;
import dev.julia.runtime.ForeignWindow;
import java.nio.file.Files;
import java.nio.file.Path;
import org.lwjgl.system.MemoryStack;

/** Full-backend frames presented into a borrowed game window, followed by a complete detach. */
public final class WindowProbe {
    private static final System.Logger LOG = System.getLogger(WindowProbe.class.getName());

    private WindowProbe() {}

    public static void run(Path destination, ForeignWindow window) throws Exception {
        Path output = destination.toAbsolutePath();
        Files.createDirectories(output);
        Files.deleteIfExists(output.resolve("result.txt"));
        Files.deleteIfExists(output.resolve("window-texture.png"));
        String result;
        try (var video = HostedVideo.start();
                var borrowed = BorrowedWindow.borrow(window);
                var binding = bind(borrowed.handle());
                var session = GlsmSession.open(
                        video, binding.handle(), () -> pixelSize(borrowed.handle()), "Julia window probe")) {
            var size = session.size();
            AdapterScene.window(output, size.width(), size.height());
            Sdl.require(SDL_WaitForGPUIdle(session.device().getDevice()), "Wait for presented frames");
            result = """
                    PASS
                    backend=%s
                    window=borrowed %s
                    frames_presented=%d
                    size=%dx%d
                    verified_pixels=%d
                    process_id=%d
                    thread=%s
                    """.formatted(
                            session.backend().getName(),
                            window.getClass().getSimpleName(),
                            AdapterScene.FRAMES,
                            size.width(),
                            size.height(),
                            size.pixels(),
                            ProcessHandle.current().pid(),
                            Thread.currentThread().getName());
        }
        // Reaching here means every borrowed window verified its restored native state on close.
        Files.writeString(output.resolve("result.txt"), result + "native_window_state_restored=true\n");
        System.out.println("[Julia] PASS: " + AdapterScene.FRAMES
                + " frames presented into the game window; native window state restored");
        LOG.log(System.Logger.Level.INFO, "Window probe passed");
    }

    private static HostWindow bind(long window) {
        var logical = size(window, false, "Read logical window dimensions");
        return HostWindow.bind(window, logical.width(), logical.height());
    }

    private static GlsmSession.Size pixelSize(long window) {
        return size(window, true, "Read window pixel dimensions");
    }

    private static GlsmSession.Size size(long window, boolean pixels, String operation) {
        try (var stack = MemoryStack.stackPush()) {
            var width = stack.ints(0);
            var height = stack.ints(0);
            Sdl.require(
                    pixels
                            ? SDL_GetWindowSizeInPixels(window, width, height)
                            : SDL_GetWindowSize(window, width, height),
                    operation);
            return new GlsmSession.Size(width.get(0), height.get(0));
        }
    }
}
