package dev.julia.probe.host;

import java.lang.reflect.InvocationTargetException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.regex.Pattern;

/** Creates the same adapted runtime from either a CLI classpath or ZombieBuddy's nested libraries. */
public final class AdapterLauncher {
    private AdapterLauncher() { }

    public static void run(Path output) throws Exception {
        runIsolated("dev.julia.probe.adapter.AdapterProbe", output);
    }

    public static void runSuite(Path output) throws Exception {
        runIsolated("dev.julia.probe.ProbeSuite", output);
    }

    public static void runWindowSuite(Path output, long cocoaWindow) throws Exception {
        if (cocoaWindow == 0) throw new IllegalArgumentException("Cocoa window is null");
        runIsolated("dev.julia.probe.WindowSuite", output, cocoaWindow);
    }

    private static void runIsolated(String entryPoint, Path output) throws Exception {
        runIsolated(entryPoint, output, 0);
    }

    private static void runIsolated(String entryPoint, Path output, long cocoaWindow) throws Exception {
        ClassLoader current = AdapterLauncher.class.getClassLoader();
        URL[] libraries = current instanceof URLClassLoader urls ? urls.getURLs()
                : Arrays.stream(System.getProperty("java.class.path").split(Pattern.quote(java.io.File.pathSeparator)))
                    .map(Path::of).map(Path::toUri).map(uri -> {
                        try { return uri.toURL(); }
                        catch (java.net.MalformedURLException exception) { throw new IllegalArgumentException(exception); }
                    }).toArray(URL[]::new);
        ClassLoader context = Thread.currentThread().getContextClassLoader();
        String presenter = System.getProperty("angelica.sdlgpu.disablePresenterThread");
        try (var adapted = new LegacyRuntimeLoader(libraries)) {
            System.setProperty("angelica.sdlgpu.disablePresenterThread", "true");
            Thread.currentThread().setContextClassLoader(adapted);
            try {
                Class<?> entry = Class.forName(entryPoint, true, adapted);
                if (cocoaWindow == 0) entry.getMethod("run", Path.class).invoke(null, output);
                else entry.getMethod("run", Path.class, long.class).invoke(null, output, cocoaWindow);
            } catch (InvocationTargetException exception) {
                if (exception.getCause() instanceof Exception cause) throw cause;
                if (exception.getCause() instanceof Error cause) throw cause;
                throw exception;
            }
        } finally {
            Thread.currentThread().setContextClassLoader(context);
            if (presenter == null) System.clearProperty("angelica.sdlgpu.disablePresenterThread");
            else System.setProperty("angelica.sdlgpu.disablePresenterThread", presenter);
        }
    }
}
