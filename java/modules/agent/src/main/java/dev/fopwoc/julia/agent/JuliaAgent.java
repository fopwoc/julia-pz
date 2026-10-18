package dev.fopwoc.julia.agent;

import dev.fopwoc.julia.agent.audit.RuntimeAudit;
import dev.fopwoc.julia.utils.logging.LogConfiguration;
import java.lang.instrument.Instrumentation;
import java.nio.file.Path;

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
