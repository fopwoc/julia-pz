package dev.julia.agent;

import dev.julia.agent.audit.RuntimeAudit;
import dev.julia.agent.logging.LogConfiguration;
import java.lang.instrument.Instrumentation;
import java.nio.file.Path;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class JuliaAgent {
    private static final Logger LOG = Logger.getLogger(JuliaAgent.class.getName());

    private JuliaAgent() { }

    public static void premain(String arguments, Instrumentation instrumentation) throws Exception {
        LogConfiguration.configure();
        LOG.info("Starting Julia audit agent");
        if (arguments == null || arguments.isBlank()) {
            throw new IllegalArgumentException("Supply a new report path: -javaagent:julia-agent.jar=/path/report.tsv");
        }
        var audit = new RuntimeAudit(Path.of(arguments));
        LOG.info("Registering graphics class observer");
        instrumentation.addTransformer(audit);
        Runtime.getRuntime().addShutdownHook(Thread.ofPlatform().name("julia-audit-report").unstarted(() -> {
            try {
                audit.close();
            } catch (Exception exception) {
                LOG.log(Level.SEVERE, "Cannot write runtime graphics report", exception);
            }
        }));
        LOG.info("Julia ready in audit mode; rendering calls are unchanged");
    }
}
