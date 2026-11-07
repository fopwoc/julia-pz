package dev.fopwoc.julia.launch;

import dev.fopwoc.julia.utils.io.NestedLibraries;
import dev.fopwoc.julia.utils.logging.LogConfiguration;
import java.lang.instrument.Instrumentation;
import java.lang.management.ManagementFactory;
import java.lang.reflect.InvocationTargetException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.JarFile;

/** Arms live graphics redirection before game loading; each way of wiring Julia into the game ends up here. */
public final class Launcher {
    private static final System.Logger LOG = System.getLogger(Launcher.class.getName());

    private Launcher() {}

    /**
     * {@code archive} is the running entry's own jar, passed in because a preload mod's classes have no code source.
     * {@code arguments} is {@code activate}, or {@code activate=<dir>} to choose the report directory.
     */
    public static void activate(String arguments, Instrumentation instrumentation, Path archive) throws Exception {
        LogConfiguration.configure();
        Path output;
        if (arguments.equals("activate")) {
            Path root = Path.of(System.getProperty("user.home"), "Zomboid", "julia");
            Files.createDirectories(root);
            output = Files.createTempDirectory(root, "run-");
        } else {
            output = Path.of(arguments.substring("activate=".length())).toAbsolutePath();
            Files.createDirectories(output);
        }
        LOG.log(System.Logger.Level.INFO, "Preparing live renderer; output={0}", output);
        System.setProperty("julia.archive", archive.toRealPath().toString());
        try (var jar = new JarFile(archive.toFile())) {
            var version = jar.getManifest().getMainAttributes().getValue("Implementation-Version");
            if (version != null) System.setProperty("julia.version", version);
        }
        var loader = NestedLibraries.open(archive, output.resolve("runtime"), Launcher.class.getClassLoader());
        Path[] inputs = StartupArchives.collect(
                        System.getProperty("java.class.path"),
                        ManagementFactory.getRuntimeMXBean().getInputArguments(),
                        archive,
                        Path.of(System.getProperty("user.dir")))
                .toArray(Path[]::new);
        try {
            Class.forName("dev.fopwoc.julia.host.GameStartup", true, loader)
                    .getMethod("install", Instrumentation.class, Path.class, Path[].class, ClassLoader.class)
                    .invoke(null, instrumentation, output, inputs, ClassLoader.getSystemClassLoader());
        } catch (InvocationTargetException failure) {
            throw new IllegalStateException("Cannot install Julia startup", failure.getCause());
        }
    }
}
