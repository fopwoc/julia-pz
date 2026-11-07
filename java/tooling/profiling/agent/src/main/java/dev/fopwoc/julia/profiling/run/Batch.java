package dev.fopwoc.julia.profiling.run;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** Launches each planned run in turn and stops at the first failure, since later numbers couldn't be compared anyway. */
final class Batch {
    private static final Duration QUIT_GRACE = Duration.ofSeconds(60);

    private Batch() {}

    /** {@code sides} and {@code earlier} carry a comparison against a saved run, which then needs only one side here. */
    static String run(
            Path root,
            String sceneText,
            Scene scene,
            List<RunPlan> plans,
            Duration startupTimeout,
            Report.Sides sides,
            List<Report.Capture> earlier,
            List<String> notes)
            throws Exception {
        Files.createDirectories(root);
        var captures = new ArrayList<>(earlier);
        var julia = new ArrayList<Path>();
        var machines = new ArrayList<String>();
        for (var plan : plans) {
            System.out.println("Running " + plan.run().getFileName());
            launch(plan, sceneText, scene, startupTimeout);
            if (plan.renderer().equals("julia")) julia.add(plan.run());
            var machine = plan.run().resolve("machine.txt");
            if (Files.isRegularFile(machine)) {
                var line = plan.renderer() + ": " + Files.readString(machine).strip();
                if (!machines.contains(line)) machines.add(line);
            }
            for (var spot : scene.spots())
                captures.add(new Report.Capture(
                        spot.name(),
                        plan.renderer(),
                        plan.run().resolve("captures").resolve(spot.name())));
        }
        var top = new ArrayList<>(machines);
        top.addAll(notes);
        var summary = Report.summarize(scene.name(), captures, sides, top);
        var counters = Report.counters(julia);
        if (!counters.isEmpty()) summary += "\n" + String.join("\n", counters) + "\n";
        Files.writeString(root.resolve("summary.md"), summary);
        return summary;
    }

    private static void launch(RunPlan plan, String sceneText, Scene scene, Duration startupTimeout) throws Exception {
        Files.createDirectories(plan.run());
        plan.prepare();
        // Presence switches the agent from passive observing to driving the game through this scene.
        Files.writeString(plan.run().resolve("scene.json"), sceneText);
        var process = new ProcessBuilder(plan.command())
                .directory(plan.game().workingDirectory().toFile())
                .redirectErrorStream(true)
                .redirectOutput(plan.run().resolve("game-output.log").toFile())
                .start();
        try {
            var perSpot = scene.warmup().plus(scene.duration()).plus(startupTimeout);
            var limit = System.nanoTime()
                    + perSpot.multipliedBy(scene.spots().size())
                            .plus(startupTimeout)
                            .plus(QUIT_GRACE)
                            .toNanos();
            var status = plan.run().resolve("automation.txt");
            while (!process.waitFor(500, TimeUnit.MILLISECONDS)) {
                if (readStatus(status).startsWith("ERROR")) throw new IllegalStateException(failure(plan, status));
                if (System.nanoTime() > limit)
                    throw new IllegalStateException("Timed out at " + readStatus(status) + "; see " + plan.run());
            }
            if (!readStatus(status).startsWith("DONE")) throw new IllegalStateException(failure(plan, status));
        } finally {
            // Only ever this batch's own process, never a session the player started.
            if (process.isAlive()) {
                process.destroy();
                if (!process.waitFor(10, TimeUnit.SECONDS)) process.destroyForcibly();
            }
        }
    }

    private static String failure(RunPlan plan, Path status) throws IOException {
        var reported = readStatus(status);
        return (reported.isEmpty() ? "Game exited before the scene ran" : reported) + "; see "
                + plan.run().resolve("game-output.log");
    }

    private static String readStatus(Path status) throws IOException {
        return Files.exists(status) ? Files.readString(status).strip() : "";
    }
}
