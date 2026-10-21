package dev.fopwoc.julia.host;

import dev.fopwoc.julia.runtime.FatalGraphics;
import dev.fopwoc.julia.runtime.ForeignWindow;
import dev.fopwoc.julia.runtime.IsolatedTargets;
import dev.fopwoc.julia.runtime.RenderSession;
import dev.fopwoc.julia.transform.Endpoint;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URLClassLoader;
import java.nio.file.Path;

/** Persistent graphics runtime bound to the game render thread, with the consumer's native GL dispatch preserved. */
final class LiveRuntime implements AutoCloseable, RenderSession.Frames {
    private static final String PRESENTER = "angelica.sdlgpu.disablePresenterThread";
    private final String previousPresenter;
    private final RuntimeLoader loader;
    private final GameDisplay display;
    private final Method beforeCall, begin, present, abort, shutdown;
    private RenderSession session;
    private boolean recording;

    private LiveRuntime(ClassLoader host, RuntimeLoader prepared) throws ReflectiveOperationException {
        display = new GameDisplay(host);
        previousPresenter = System.getProperty(PRESENTER);
        System.setProperty(PRESENTER, "true");
        loader = prepared != null ? prepared : newLoader();
        var engine = Class.forName("dev.fopwoc.julia.backend.runtime.GameRuntime", false, loader);
        beforeCall = engine.getMethod("beforeCall");
        begin = engine.getMethod("begin");
        present = engine.getMethod("present");
        abort = engine.getMethod("abort");
        shutdown = engine.getMethod("close");
    }

    private static RuntimeLoader newLoader() {
        var libraries = (URLClassLoader) LiveRuntime.class.getClassLoader();
        return new RuntimeLoader(libraries.getURLs(), libraries, "");
    }

    /** Metal chooses HUD instrumentation at its first device, before PZ's GL context exists. */
    static RuntimeLoader prepareDeveloperHud() throws Exception {
        if (!Boolean.getBoolean("julia.metalHud")) return null;
        var loader = newLoader();
        try {
            Class.forName("dev.fopwoc.julia.backend.sdl.SdlDeveloperHud", true, loader)
                    .getMethod("prepareIfRequested")
                    .invoke(null);
            return loader;
        } catch (Exception | Error failure) {
            loader.close();
            throw failure;
        }
    }

    static LiveRuntime open(ClassLoader host, Path output, RuntimeLoader prepared) throws Exception {
        var runtime = new LiveRuntime(host, prepared);
        try {
            runtime.session = RenderSession.open(new IsolatedTargets(runtime.loader, Endpoint.owners()), runtime);
            var engine = Class.forName("dev.fopwoc.julia.backend.runtime.GameRuntime", true, runtime.loader);
            runtime.invoke(engine.getMethod("open", ForeignWindow.class, Path.class), runtime.display.window(), output);
            runtime.recording = true;
            return runtime;
        } catch (Exception | Error failure) {
            FatalGraphics.failure("Live graphics initialization failed", failure);
            try {
                runtime.close();
            } catch (Exception | Error cleanup) {
                failure.addSuppressed(cleanup);
            }
            throw failure;
        } finally {
            runtime.display.restoreDispatch();
        }
    }

    private void invoke(Method method, Object... arguments) {
        var thread = Thread.currentThread();
        var previous = thread.getContextClassLoader();
        try {
            thread.setContextClassLoader(loader);
            method.invoke(null, arguments);
        } catch (InvocationTargetException failure) {
            var cause = failure.getCause();
            throw FatalGraphics.failure("Runtime operation failed: " + method.getName(), cause);
        } catch (ReflectiveOperationException failure) {
            throw FatalGraphics.failure("Cannot invoke runtime operation: " + method.getName(), failure);
        } finally {
            thread.setContextClassLoader(previous);
        }
    }

    @Override
    public void beforeCall() {
        if (!recording) {
            invoke(beforeCall);
            recording = true;
        }
    }

    @Override
    public void begin() {
        invoke(begin);
        recording = true;
    }

    @Override
    public void present() {
        invoke(present);
        recording = false;
    }

    @Override
    public void abort() {
        invoke(abort);
        recording = false;
    }

    @Override
    public void close() {
        // RenderSession invokes Frames.close(): separate the session owner from the engine's close callback.
        if (session != null) {
            var active = session;
            session = null;
            active.close();
            return;
        }
        try {
            invoke(shutdown);
        } finally {
            try {
                loader.close();
                display.restoreDispatch();
                if (previousPresenter == null) System.clearProperty(PRESENTER);
                else System.setProperty(PRESENTER, previousPresenter);
            } catch (Exception failure) {
                throw new IllegalStateException("Cannot close graphics loader", failure);
            }
        }
    }
}
