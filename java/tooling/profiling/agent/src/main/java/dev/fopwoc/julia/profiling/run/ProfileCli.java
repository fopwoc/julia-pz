package dev.fopwoc.julia.profiling.run;

import dev.fopwoc.julia.profiling.agent.FrameAudit;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/** The tool's commands: {@code run <scene>} is the whole comparison, the rest support it. */
public final class ProfileCli {
    private static final Path ZOMBOID = Path.of(System.getProperty("user.home"), "Zomboid");
    private static final Path TEMPLATE = ZOMBOID.resolve("profiling-template");
    private static final Set<String> SWITCHES = Set.of("--dry-run");

    private ProfileCli() {}

    public static void main(String[] arguments) throws Exception {
        try {
            if (arguments.length == 0) throw new IllegalArgumentException(usage());
            var rest = new ArrayList<String>();
            var options = options(arguments, rest);
            switch (arguments[0]) {
                case "run" -> run(one(rest, "a scene file"), options);
                case "bake" -> {
                    var output = Path.of(options.getOrDefault("--out", TEMPLATE.toString()));
                    Bake.bake(
                            Path.of(options.getOrDefault("--source", ZOMBOID.toString())),
                            one(rest, "a Mode/SaveName"),
                            output);
                    System.out.println("Baked " + output);
                }
                case "compare" -> {
                    if (rest.size() != 2) throw new IllegalArgumentException("Supply two capture folders");
                    System.out.print(Report.compare(Path.of(rest.get(0)), Path.of(rest.get(1))));
                }
                case "audit" -> FrameAudit.main(new String[] {one(rest, "projectzomboid.jar")});
                default -> throw new IllegalArgumentException(usage());
            }
        } catch (IllegalArgumentException | IllegalStateException failure) {
            System.err.println("error: " + failure.getMessage());
            System.exit(2);
        }
    }

    private static void run(String sceneArgument, Map<String, String> options) throws Exception {
        var sceneFile = Path.of(sceneArgument).toAbsolutePath();
        var scene = Scene.read(sceneFile);
        int repetitions = Integer.parseInt(options.getOrDefault("--repetitions", "1"));
        if (repetitions < 1 || repetitions > 100) throw new IllegalArgumentException("Use 1..100 repetitions");
        var template =
                Path.of(options.getOrDefault("--template", TEMPLATE.toString())).toAbsolutePath();
        if (!Files.isRegularFile(template.resolve("Saves").resolve(scene.save()).resolve("map_ver.bin")))
            throw new IllegalArgumentException(
                    "No Saves/" + scene.save() + " in " + template + "; run: bake " + scene.save());
        var stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
        var root = Path.of(options.getOrDefault(
                        "--out",
                        ZOMBOID.resolve("profiling")
                                .resolve(scene.name() + "-" + stamp)
                                .toString()))
                .toAbsolutePath();
        if (Files.exists(root)) throw new IllegalArgumentException("Choose a fresh output folder: " + root);
        if (!options.containsKey("--game"))
            throw new IllegalArgumentException("Supply --game; the launch scripts find it");
        var game = GameInstall.read(Path.of(options.get("--game")), System.getProperty("os.arch"));
        Path buddy = null;
        if (scene.zombieBuddy()) {
            buddy = options.containsKey("--buddy")
                    ? Path.of(options.get("--buddy")).toAbsolutePath()
                    : game.workingDirectory().resolve("ZombieBuddy.jar");
            if (!Files.isRegularFile(buddy))
                throw new IllegalArgumentException("The scene needs ZombieBuddy, but there's no " + buddy);
        }
        var plans = new ArrayList<RunPlan>();
        for (int repeat = 0; repeat < repetitions; repeat++) {
            // Alternating keeps warm OS caches from always favoring whichever renderer runs second.
            var order = repeat % 2 == 0 ? new String[] {"opengl", "julia"} : new String[] {"julia", "opengl"};
            for (var renderer : order) {
                plans.add(RunPlan.of(
                        renderer,
                        root.resolve(String.format("%02d-%s", repeat + 1, renderer)),
                        template,
                        agentJar(),
                        buddy,
                        ZOMBOID.getParent().resolve(".zombie_buddy"),
                        scene,
                        game));
            }
        }
        if (options.containsKey("--dry-run")) {
            for (var plan : plans) System.out.println(String.join(" ", plan.command()));
            return;
        }
        if (sessionRunning())
            throw new IllegalStateException("Close the game first: a batch needs the machine to itself");
        var timeout = Duration.ofSeconds(Long.parseLong(options.getOrDefault("--startup-timeout", "300")));
        System.out.print(Batch.run(root, sceneFile, scene, plans, timeout));
        System.out.println("Results in " + root);
    }

    private static boolean sessionRunning() {
        var self = ProcessHandle.current().pid();
        return ProcessHandle.allProcesses().anyMatch(process -> {
            var command = process.info().commandLine().orElse("");
            return process.pid() != self
                    && (command.contains("zombie.gameStates.MainScreenState")
                            || command.contains("Project Zomboid.app/Contents/MacOS")
                            || command.contains("ProjectZomboid64"));
        });
    }

    private static Path agentJar() throws URISyntaxException {
        return Path.of(ProfileCli.class
                .getProtectionDomain()
                .getCodeSource()
                .getLocation()
                .toURI());
    }

    private static Map<String, String> options(String[] arguments, ArrayList<String> rest) {
        var result = new HashMap<String, String>();
        for (int at = 1; at < arguments.length; at++) {
            var argument = arguments[at];
            if (!argument.startsWith("--")) rest.add(argument);
            else if (SWITCHES.contains(argument)) result.put(argument, "");
            else if (++at < arguments.length) result.put(argument, arguments[at]);
            else throw new IllegalArgumentException(argument + " needs a value");
        }
        return result;
    }

    private static String one(ArrayList<String> rest, String what) {
        if (rest.size() != 1) throw new IllegalArgumentException("Supply " + what);
        return rest.getFirst();
    }

    private static String usage() {
        return """
                Commands:
                  run <scene.json> --game <folder> [--repetitions n] [--template dir] [--out dir] [--dry-run]
                  bake <Mode/SaveName> [--source dir] [--out dir]
                  compare <opengl capture> <julia capture>
                  audit <projectzomboid.jar>""";
    }
}
