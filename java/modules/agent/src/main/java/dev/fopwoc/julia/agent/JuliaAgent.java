package dev.fopwoc.julia.agent;

import dev.fopwoc.julia.agent.audit.RuntimeAudit;
import dev.fopwoc.julia.utils.io.NestedLibraries;
import dev.fopwoc.julia.utils.logging.LogConfiguration;
import java.io.File;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.InvocationTargetException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.regex.Pattern;

/** Startup agent that inventories graphics references in loaded game classes without changing them. */
public final class JuliaAgent {
    private static final System.Logger LOG = System.getLogger(JuliaAgent.class.getName());

    private JuliaAgent() {}

    public static void premain(String arguments, Instrumentation instrumentation) throws Exception {
        LogConfiguration.configure();
        LOG.log(System.Logger.Level.INFO, "Starting Julia audit agent");
        if (arguments == null || arguments.isBlank()) {
            throw new IllegalArgumentException("Supply a new report path: -javaagent:julia-agent.jar=/path/report.tsv");
        }
        if (arguments.startsWith("activate=")) {
            Path output = Path.of(arguments.substring("activate=".length())).toAbsolutePath();
            Files.createDirectories(output);
            Path archive = Path.of(JuliaAgent.class
                    .getProtectionDomain()
                    .getCodeSource()
                    .getLocation()
                    .toURI());
            var loader = NestedLibraries.open(archive, output.resolve("runtime"), JuliaAgent.class.getClassLoader());
            Path[] inputs = Arrays.stream(
                            System.getProperty("java.class.path").split(Pattern.quote(File.pathSeparator)))
                    .map(Path::of)
                    .filter(path -> Files.isRegularFile(path) && path.toString().endsWith(".jar"))
                    .filter(path -> !path.toAbsolutePath().equals(archive.toAbsolutePath()))
                    .toArray(Path[]::new);
            try {
                Class.forName("dev.fopwoc.julia.host.GameStartup", true, loader)
                        .getMethod("install", Instrumentation.class, Path.class, Path[].class, ClassLoader.class)
                        .invoke(null, instrumentation, output, inputs, ClassLoader.getSystemClassLoader());
            } catch (InvocationTargetException failure) {
                throw new IllegalStateException("Cannot install Julia startup", failure.getCause());
            }
            return;
        }
        var audit = new RuntimeAudit(Path.of(arguments));
        LOG.log(System.Logger.Level.INFO, "Registering graphics class observer");
        instrumentation.addTransformer(audit);
        Runtime.getRuntime()
                .addShutdownHook(Thread.ofPlatform().name("julia-audit-report").unstarted(() -> {
                    try {
                        audit.close();
                    } catch (Exception exception) {
                        LOG.log(System.Logger.Level.ERROR, "Cannot write runtime graphics report", exception);
                    }
                }));
        LOG.log(System.Logger.Level.INFO, "Julia ready in audit mode; rendering calls are unchanged");
    }
}
