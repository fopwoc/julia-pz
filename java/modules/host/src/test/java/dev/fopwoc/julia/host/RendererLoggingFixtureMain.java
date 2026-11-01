package dev.fopwoc.julia.host;

import dev.fopwoc.julia.utils.logging.LogConfiguration;
import java.io.File;
import java.net.URL;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Pattern;

/** A fresh JVM keeps LOG_LEVEL, JUL handlers and Log4j provider caches independent between test cases. */
public final class RendererLoggingFixtureMain {
    private RendererLoggingFixtureMain() {}

    public static void main(String[] arguments) throws Exception {
        var root = Logger.getLogger("");
        var rootLevel = root.getLevel();
        var rootHandlers = root.getHandlers();
        boolean rootParents = root.getUseParentHandlers();
        var unrelated = Logger.getLogger("game.unrelated.logging");
        unrelated.setLevel(Level.FINEST);
        var unrelatedHandlers = unrelated.getHandlers();
        boolean unrelatedParents = unrelated.getUseParentHandlers();
        LogConfiguration.configure();
        var namespaces = List.of(
                Logger.getLogger("dev.fopwoc.julia"),
                Logger.getLogger("com.gtnewhorizons.angelica"),
                Logger.getLogger("com.gtnewhorizon.gtnhlib"));
        var handlers = namespaces.stream().map(Logger::getHandlers).toList();
        for (var configured : handlers) {
            if (configured.length != 1) throw new AssertionError("Expected one namespace handler");
        }
        var libraries = new ArrayList<URL>();
        for (String entry : System.getProperty("java.class.path").split(Pattern.quote(File.pathSeparator))) {
            libraries.add(Path.of(entry).toUri().toURL());
        }
        var thread = Thread.currentThread();
        var previous = thread.getContextClassLoader();
        for (int run = 0; run < 2; run++) {
            try (var loader = new RuntimeLoader(
                    libraries.toArray(URL[]::new), RendererLoggingFixtureMain.class.getClassLoader(), "")) {
                try {
                    thread.setContextClassLoader(loader);
                    Class.forName("dev.fopwoc.julia.host.RendererLoggingEmitter", true, loader)
                            .getMethod("emit")
                            .invoke(null);
                } finally {
                    thread.setContextClassLoader(previous);
                }
            }
            for (int i = 0; i < namespaces.size(); i++) {
                if (!Arrays.equals(handlers.get(i), namespaces.get(i).getHandlers())) {
                    throw new AssertionError("Reloading replaced or duplicated namespace handlers");
                }
            }
        }
        if (rootLevel != root.getLevel()
                || rootParents != root.getUseParentHandlers()
                || !Arrays.equals(rootHandlers, root.getHandlers())
                || unrelated.getLevel() != Level.FINEST
                || unrelatedParents != unrelated.getUseParentHandlers()
                || !Arrays.equals(unrelatedHandlers, unrelated.getHandlers())) {
            throw new AssertionError("Julia changed unrelated game logging");
        }
        System.out.println("logging-fixture-ok");
    }
}
