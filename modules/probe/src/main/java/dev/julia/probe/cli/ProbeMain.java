package dev.julia.probe.cli;

import dev.julia.probe.ProbeLauncher;
import dev.julia.probe.device.OffscreenProbe;
import dev.julia.transform.audit.PreparationAudit;
import dev.julia.transform.audit.RedirectAudit;
import dev.julia.utils.logging.LogConfiguration;
import java.nio.file.Path;
import java.util.Arrays;

/** Command-line entry for offline audits, linkage checks, and explicitly requested native probes. */
public final class ProbeMain {
    private static final System.Logger LOG = System.getLogger(ProbeMain.class.getName());
    private static final String USAGE = String.join(
            System.lineSeparator(),
            "Usage: probe <command>",
            "  [game.jar ...]                      check that Angelica's entry points link",
            "  " + RedirectAudit.USAGE,
            "  " + PreparationAudit.USAGE,
            "  --render <dir>                      raw-device clear and translated draw (main thread)",
            "  --adapter <dir>                     full GLSM adapter in an isolated runtime",
            "  --suite <dir>                       --render then --adapter, as the game mod runs them");

    private ProbeMain() {}

    public static void main(String[] args) {
        LogConfiguration.configure();
        try {
            System.exit(run(args));
        } catch (Exception | LinkageError failure) {
            LOG.log(System.Logger.Level.ERROR, "Probe failed", failure);
            System.exit(1);
        }
    }

    private static int run(String[] args) throws Exception {
        String command = args.length == 0 ? "" : args[0];
        String[] rest = args.length == 0 ? args : Arrays.copyOfRange(args, 1, args.length);
        switch (command) {
            case "--help" -> System.out.println(USAGE);
            case "--audit-redirects" -> RedirectAudit.run(rest, System.out);
            case "--prepare-runtime" -> PreparationAudit.run(rest, System.out);
            case "--render" -> OffscreenProbe.run(directory(rest));
            case "--adapter" -> ProbeLauncher.runAdapter(directory(rest));
            case "--suite" -> ProbeLauncher.runSuite(directory(rest));
            default -> {
                if (command.startsWith("--"))
                    throw new IllegalArgumentException("Unknown command " + command + "\n" + USAGE);
                return LinkageCheck.run(args, System.out, System.err);
            }
        }
        return 0;
    }

    private static Path directory(String[] rest) {
        if (rest.length != 1) throw new IllegalArgumentException("Expected one output directory\n" + USAGE);
        return Path.of(rest[0]);
    }
}
