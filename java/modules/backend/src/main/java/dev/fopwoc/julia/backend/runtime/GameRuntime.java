package dev.fopwoc.julia.backend.runtime;

import static org.lwjgl.sdl.SDLGPU.SDL_CancelGPUCommandBuffer;
import static org.lwjgl.sdl.SDLGPU.SDL_WaitForGPUIdle;
import static org.lwjgl.sdl.SDLVideo.SDL_GetWindowSize;
import static org.lwjgl.sdl.SDLVideo.SDL_GetWindowSizeInPixels;

import com.gtnewhorizons.angelica.glsm.profiling.DebugCounters;
import com.gtnewhorizons.angelica.sdlgpu.SDLGPUGate;
import com.gtnewhorizons.angelica.sdlgpu.SDLGPURenderBackend;
import dev.fopwoc.julia.backend.Subsystems;
import dev.fopwoc.julia.backend.Upstream;
import dev.fopwoc.julia.backend.platform.Platform;
import dev.fopwoc.julia.backend.utils.Sdl;
import dev.fopwoc.julia.backend.window.BorrowedWindow;
import dev.fopwoc.julia.backend.window.HostWindow;
import dev.fopwoc.julia.runtime.ForeignWindow;
import java.nio.file.Files;
import java.nio.file.Path;
import org.lwjgl.system.MemoryStack;

/** Persistent backend lifecycle. Initialization uploads share the first frame rather than being discarded at begin. */
public final class GameRuntime {
    private static final System.Logger LOG = System.getLogger(GameRuntime.class.getName());
    private static GameRuntime current;
    private HostedVideo video;
    private BorrowedWindow window;
    private HostWindow binding;
    private GlsmSession session;
    private Path output;
    private FrameWatchdog watchdog;
    private long frames;
    private GlsmSession.Size renderSize;

    private GameRuntime() {}

    public static void open(ForeignWindow foreign, Path output) {
        if (current != null) throw new IllegalStateException("Game runtime is already open");
        Platform.current().gpuBackend().requireImplemented();
        var runtime = new GameRuntime();
        current = runtime;
        runtime.output = output;
        try {
            runtime.video = HostedVideo.start(foreign);
            runtime.window = BorrowedWindow.borrow(foreign);
            var logical = size(runtime.window.handle(), false);
            runtime.binding = HostWindow.bind(runtime.window.handle(), logical.width(), logical.height());
            runtime.binding.useLogicalResolution();
            runtime.session = GlsmSession.open(
                    runtime.video,
                    runtime.binding.handle(),
                    () -> size(runtime.window.handle(), false),
                    "Julia live renderer");
            Subsystems.current().drawTrace().open(output);
            beforeCall();
            InitialFrame.present(runtime.session.backend());
            beforeCall();
            System.setProperty("julia.renderer.backend", SDLGPUGate.device().getDriverName());
            runtime.status("READY");
            runtime.watchdog = new FrameWatchdog(output);
            LOG.log(
                    System.Logger.Level.INFO,
                    "Live renderer ready on {0}",
                    Thread.currentThread().getName());
        } catch (RuntimeException | Error failure) {
            try {
                close();
            } catch (RuntimeException | Error cleanup) {
                failure.addSuppressed(cleanup);
            }
            throw failure;
        }
    }

    public static void beforeCall() {
        var runtime = require();
        if (!Upstream.frames().isFrameActive()) {
            var logical = runtime.renderSize != null ? runtime.renderSize : size(runtime.window.handle(), false);
            runtime.binding.dimensions(logical.width(), logical.height());
            runtime.session.backend().onFrameBegin();
        }
    }

    public static void begin() {
        require().renderSize = null;
        beforeCall();
    }

    public static void begin(int width, int height) {
        var runtime = require();
        runtime.renderSize = new GlsmSession.Size(width, height);
        runtime.binding.dimensions(width, height);
        beforeCall();
        DefaultTarget.resize(width, height);
    }

    public static void present() {
        var runtime = require();
        Subsystems.current().drawTrace().frameBoundary();
        runtime.session.backend().onFrameEnd();
        runtime.frames++;
        runtime.watchdog.presented(runtime.frames);
        if (runtime.frames <= 3 || runtime.frames % 60 == 0) runtime.status("RENDERING");
    }

    public static void abort() {
        if (current == null || current.session == null) return;
        var backend = (SDLGPURenderBackend) current.session.backend();
        backend.endComputeDispatchBatch();
        var frames = Upstream.frames();
        var frame = frames.frame();
        frames.endRenderPassIfActive(frame);
        frames.endCopyPassIfActive();
        if (frame.commandBuffer != 0) {
            long command = frame.commandBuffer;
            frame.commandBuffer = 0;
            Sdl.require(SDL_CancelGPUCommandBuffer(command), "Cancel aborted game frame");
        }
        frame.frameActive = false;
    }

    public static void close() {
        var runtime = current;
        if (runtime == null) return;
        Throwable failure = null;
        try {
            if (runtime.session != null) {
                abort();
                Sdl.require(SDL_WaitForGPUIdle(runtime.session.device().getDevice()), "Drain game renderer");
            }
        } catch (RuntimeException | Error error) {
            failure = error;
        }
        for (AutoCloseable resource :
                new AutoCloseable[] {runtime.watchdog, runtime.session, runtime.binding, runtime.window, runtime.video
                }) {
            if (resource == null) continue;
            try {
                resource.close();
            } catch (Exception | Error error) {
                if (failure == null) failure = error;
                else if (failure != error) failure.addSuppressed(error);
            }
        }
        current = null;
        System.clearProperty("julia.renderer.backend");
        if (failure instanceof Error error) throw error;
        if (failure != null) throw new IllegalStateException("Game renderer cleanup failed", failure);
        LOG.log(System.Logger.Level.INFO, "Live renderer closed; borrowed window restored");
    }

    private void status(String stage) {
        try {
            Files.writeString(
                    output.resolve("renderer.txt"),
                    stage + "\nbackend=" + session.backend().getName()
                            + "\nthread=" + Thread.currentThread().getName() + "\nframes=" + frames
                            + "\npresents=" + DebugCounters.PRESENTS.sum() + "\n");
        } catch (Exception failure) {
            throw new IllegalStateException("Cannot write renderer status", failure);
        }
    }

    private static GameRuntime require() {
        if (current == null || current.session == null) throw new IllegalStateException("Game runtime is not open");
        return current;
    }

    private static GlsmSession.Size size(long window, boolean pixels) {
        try (var stack = MemoryStack.stackPush()) {
            var width = stack.ints(0);
            var height = stack.ints(0);
            Sdl.require(
                    pixels
                            ? SDL_GetWindowSizeInPixels(window, width, height)
                            : SDL_GetWindowSize(window, width, height),
                    "Read live window dimensions");
            return new GlsmSession.Size(width.get(0), height.get(0));
        }
    }
}
