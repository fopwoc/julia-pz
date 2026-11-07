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

    static String run(Path root, Path sceneFile, Scene scene, List<RunPlan> plans, Duration startupTimeout)
            throws Exception {
        Files.createDirectories(root);
        var captures = new ArrayList<Report.Capture>();
        for (var plan : plans) {
            System.out.println("Running " + plan.run().getFileName());
            launch(plan, sceneFile, scene, startupTimeout);
            for (var spot : scene.spots())
                captures.add(new Report.Capture(
                        spot.name(),
                        plan.renderer(),
                        plan.run().resolve("captures").resolve(spot.name())));
        }
        var summary = Report.summarize(scene.name(), captures);
        Files.writeString(root.resolve("summary.md"), summary);
        return summary;
    }

    private static void launch(RunPlan plan, Path sceneFile, Scene scene, Duration startupTimeout) throws Exception {
        Files.createDirectories(plan.run());
        plan.prepare();
        // Presence switches the agent from passive observing to driving the game through this scene.
        Files.copy(sceneFile, plan.run().resolve("scene.json"));
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
