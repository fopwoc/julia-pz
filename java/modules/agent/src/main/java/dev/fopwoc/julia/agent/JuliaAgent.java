package dev.fopwoc.julia.agent;

import dev.fopwoc.julia.agent.audit.RuntimeAudit;
import dev.fopwoc.julia.utils.io.NestedLibraries;
import dev.fopwoc.julia.utils.logging.LogConfiguration;
import java.lang.instrument.Instrumentation;
import java.lang.management.ManagementFactory;
import java.lang.reflect.InvocationTargetException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Installs live graphics redirection before game loading, or inventories consumers in audit mode. */
public final class JuliaAgent {
    private static final System.Logger LOG = System.getLogger(JuliaAgent.class.getName());

    private JuliaAgent() {}

    public static void premain(String arguments, Instrumentation instrumentation) throws Exception {
        LogConfiguration.configure();
        LOG.log(System.Logger.Level.INFO, "Starting Julia agent");
        if (arguments == null || arguments.isBlank()) {
            throw new IllegalArgumentException("Supply activate, activate=/path/new-run, or an audit report path");
        }
        if (arguments.equals("activate") || arguments.startsWith("activate=")) {
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
            Path archive = Path.of(JuliaAgent.class
                    .getProtectionDomain()
                    .getCodeSource()
                    .getLocation()
                    .toURI());
            var loader = NestedLibraries.open(archive, output.resolve("runtime"), JuliaAgent.class.getClassLoader());
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
