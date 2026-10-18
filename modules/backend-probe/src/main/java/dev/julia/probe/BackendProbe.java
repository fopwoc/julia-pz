package dev.julia.probe;

import dev.julia.probe.redirect.RedirectAudit;
import dev.julia.probe.render.OffscreenProbe;
import java.io.PrintStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.regex.Pattern;

/** Entry point for linkage checks, redirect audits, and the explicit offscreen graphics probe. */
public final class BackendProbe {
    private static final String[] ENTRY_POINTS = {
            "com.gtnewhorizons.angelica.glsm.redirect.GLSMRedirector",
            "com.gtnewhorizons.angelica.glsm.GLStateManager",
            "com.gtnewhorizons.angelica.sdlgpu.SDLGPURenderBackend",
            "com.gtnewhorizons.angelica.sdlgpu.device.SDLDrawable"
    };

    private BackendProbe() { }

    public static void main(String[] args) throws Exception {
        if (args.length > 0 && args[0].equals("--prepare-runtime")) {
            dev.julia.probe.redirect.PreparationAudit.main(java.util.Arrays.copyOfRange(args, 1, args.length));
            return;
        }
        if (args.length == 2 && args[0].equals("--suite")) {
            dev.julia.probe.host.AdapterLauncher.runSuite(Path.of(args[1]));
            return;
        }
        if (args.length == 2 && args[0].equals("--adapter")) {
            dev.julia.probe.host.AdapterLauncher.run(Path.of(args[1]));
            return;
        }
        if (args.length == 2 && args[0].equals("--render")) {
            OffscreenProbe.run(Path.of(args[1]));
            return;
        }
        if (args.length > 0 && args[0].equals("--audit-redirects")) {
            RedirectAudit.main(java.util.Arrays.copyOfRange(args, 1, args.length));
            return;
        }
        var urls = new ArrayList<URL>();
        for (var entry : System.getProperty("java.class.path").split(Pattern.quote(java.io.File.pathSeparator))) {
            urls.add(Path.of(entry).toUri().toURL());
        }
        for (var argument : args) {
            urls.add(Path.of(argument).toRealPath().toUri().toURL());
        }
        int failures;
        // A shared isolated loader lets the published libraries see additional game JARs.
        try (var loader = new URLClassLoader(urls.toArray(URL[]::new), ClassLoader.getPlatformClassLoader())) {
            failures = inspect(loader, System.out, System.err);
        }
        System.exit(failures == 0 ? 0 : 1);
    }

    static int inspect(ClassLoader loader, PrintStream out, PrintStream err) {
        int failures = 0;
        for (var name : ENTRY_POINTS) {
            try {
                var type = Class.forName(name, false, loader);
                int methods = type.getDeclaredMethods().length;
                type.getDeclaredConstructors();
                type.getDeclaredFields();
                out.printf("LINKED %s (%d methods)%n", name, methods);
            } catch (ReflectiveOperationException | LinkageError exception) {
                failures++;
                out.printf("MISSING %s: %s%n", name, exception);
                exception.printStackTrace(err);
            }
        }
        out.printf("%d/%d entry points linked. No native initialization or rendering was attempted.%n",
                ENTRY_POINTS.length - failures, ENTRY_POINTS.length);
        return failures;
    }
}
