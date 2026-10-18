package dev.julia.probe.cli;

import java.io.File;
import java.io.IOException;
import java.io.PrintStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/** Links Angelica's entry points against this runtime plus the given game JARs, without native initialization. */
final class LinkageCheck {
    private static final List<String> ENTRY_POINTS = List.of(
            "com.gtnewhorizons.angelica.glsm.redirect.GLSMRedirector",
            "com.gtnewhorizons.angelica.glsm.GLStateManager",
            "com.gtnewhorizons.angelica.sdlgpu.SDLGPURenderBackend",
            "com.gtnewhorizons.angelica.sdlgpu.device.SDLDrawable");

    private LinkageCheck() {}

    /** @return process exit status: zero when every entry point links */
    static int run(String[] jars, PrintStream out, PrintStream err) throws IOException {
        var urls = new ArrayList<URL>();
        for (var entry : System.getProperty("java.class.path").split(Pattern.quote(File.pathSeparator))) {
            urls.add(Path.of(entry).toUri().toURL());
        }
        for (var jar : jars) urls.add(Path.of(jar).toRealPath().toUri().toURL());
        // One shared isolated loader lets the published libraries see the additional game JARs.
        try (var loader = new URLClassLoader(urls.toArray(URL[]::new), ClassLoader.getPlatformClassLoader())) {
            return inspect(loader, out, err) == 0 ? 0 : 1;
        }
    }

    private static int inspect(ClassLoader loader, PrintStream out, PrintStream err) {
        int failures = 0;
        for (var name : ENTRY_POINTS) {
            try {
                var type = Class.forName(name, false, loader);
                int methods = type.getDeclaredMethods().length;
                type.getDeclaredConstructors();
                type.getDeclaredFields();
                out.printf("LINKED %s (%d methods)%n", name, methods);
            } catch (ReflectiveOperationException | LinkageError failure) {
                failures++;
                out.printf("MISSING %s: %s%n", name, failure);
                failure.printStackTrace(err);
            }
        }
        out.printf(
                "%d/%d entry points linked. No native initialization or rendering was attempted.%n",
                ENTRY_POINTS.size() - failures, ENTRY_POINTS.size());
        return failures;
    }
}
