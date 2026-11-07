package dev.fopwoc.julia.profiling.run;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Properties;

/** Compares completed recordings using common frame metrics, with no renderer-specific counters. */
final class Report {
    record Capture(String spot, String renderer, Path directory) {}

    private Report() {}

    /** Which two sides a table compares, by the renderer name on their captures. */
    record Sides(String left, String leftTitle, String right, String rightTitle) {}

    static final Sides RENDERERS = new Sides("opengl", "OpenGL", "julia", "Julia");
    static final Sides BUILDS = new Sides("baseline", "Baseline", "julia", "Julia");

    static String summarize(String scene, Collection<Capture> captures) throws IOException {
        return summarize(scene, captures, RENDERERS, List.of());
    }

    static String summarize(String scene, Collection<Capture> captures, Sides sides, List<String> notes)
            throws IOException {
        var lines = new ArrayList<String>();
        lines.add("# " + scene);
        lines.add("");
        lines.addAll(notes);
        if (!notes.isEmpty()) lines.add("");
        lines.add(String.format(
                "| Spot | Runs | %s FPS | %s FPS | Difference | %s p99 ms | %s p99 ms |",
                sides.leftTitle(), sides.rightTitle(), sides.leftTitle(), sides.rightTitle()));
        lines.add("| --- | ---: | ---: | ---: | ---: | ---: | ---: |");
        int runs = 0;
        for (var spot : captures.stream().map(Capture::spot).distinct().toList()) {
            var left = metrics(captures, spot, sides.left());
            var right = metrics(captures, spot, sides.right());
            int pairs = left.isEmpty() || right.isEmpty()
                    ? Math.max(left.size(), right.size())
                    : Math.min(left.size(), right.size());
            runs = Math.max(runs, pairs);
            var change = left.isEmpty() || right.isEmpty()
                    ? "-"
                    : String.format(
                            Locale.ROOT,
                            "%+.1f%%",
                            (median(right, "average_fps") / median(left, "average_fps") - 1) * 100);
            lines.add(String.format(
                    Locale.ROOT,
                    "| %s | %d | %s | %s | %s | %s | %s |",
                    spot,
                    pairs,
                    fps(left),
                    fps(right),
                    change,
                    p99(left),
                    p99(right)));
        }
        lines.add("");
        lines.addAll(resources(captures, sides));
        lines.add("");
        if (runs < 2) lines.add("One run per side: this can't separate a real difference from run-to-run noise.");
        else lines.add("FPS is the median with its range across runs. Trust a difference only beyond those ranges.");
        lines.add("Runs are uncapped with VSync off. CPU wall timings only, not GPU timestamps.");
        return String.join("\n", lines) + "\n";
    }

    /** Broad differences only: these vary between runs far more than frame times do. */
    private static List<String> resources(Collection<Capture> captures, Sides sides) throws IOException {
        var lines = new ArrayList<String>();
        lines.add("| Spot | Side | Heap peak MB | Process MB | CPU % | Render thread % | Logic thread % |");
        lines.add("| --- | --- | ---: | ---: | ---: | ---: | ---: |");
        boolean any = false;
        for (var spot : captures.stream().map(Capture::spot).distinct().toList()) {
            for (var side : List.of(sides.left(), sides.right())) {
                var runs = new ArrayList<Properties>();
                for (var capture : captures) {
                    var file = capture.directory().resolve("metrics.properties");
                    if (capture.spot().equals(spot) && capture.renderer().equals(side) && Files.isRegularFile(file)) {
                        var metrics = new Properties();
                        try (var reader = Files.newBufferedReader(file)) {
                            metrics.load(reader);
                        }
                        runs.add(metrics);
                    }
                }
                if (runs.isEmpty()) continue;
                any = true;
                lines.add(String.format(
                        Locale.ROOT,
                        "| %s | %s | %s | %s | %s | %s | %s |",
                        spot,
                        side,
                        whole(runs, "heap_peak_mb"),
                        whole(runs, "rss_mb"),
                        whole(runs, "cpu_process_pct"),
                        whole(runs, "cpu_render_pct"),
                        whole(runs, "cpu_logic_pct")));
            }
        }
        if (!any) return List.of();
        lines.add("");
        return lines;
    }

    private static String whole(List<Properties> runs, String key) {
        var present = runs.stream().filter(run -> run.containsKey(key)).toList();
        return present.isEmpty() ? "-" : String.format(Locale.ROOT, "%.0f", median(present, key));
    }

    /** Two captures named on the command line, checked to be comparable. */
    static String compare(Path baseline, Path candidate) throws IOException {
        var first = read(baseline);
        var second = read(candidate);
        for (var key : List.of("scenario", "warmup_ms", "duration_ms"))
            if (!first.getProperty(key, "").equals(second.getProperty(key, "")))
                throw new IllegalArgumentException("Spot and capture timing must match");
        return summarize(
                first.getProperty("scenario"),
                List.of(
                        new Capture(first.getProperty("scenario"), "opengl", baseline),
                        new Capture(first.getProperty("scenario"), "julia", candidate)));
    }

    /** The Julia captures of an earlier batch, as the baseline side of a build comparison. */
    static List<Capture> baseline(Path root) throws IOException {
        var result = new ArrayList<Capture>();
        try (var runs = Files.newDirectoryStream(root, "*-julia")) {
            for (var run : runs) {
                try (var spots = Files.newDirectoryStream(run.resolve("captures"))) {
                    for (var spot : spots)
                        result.add(new Capture(spot.getFileName().toString(), "baseline", spot));
                }
            }
        }
        if (result.isEmpty()) throw new IllegalArgumentException("No Julia captures under " + root);
        return result;
    }

    /** The per-frame renderer counters of each Julia run, medians not being meaningful for a count this steady. */
    static List<String> counters(List<Path> runs) throws IOException {
        var lines = new ArrayList<String>();
        for (var run : runs) {
            var file = run.resolve("renderer/counters.txt");
            if (!Files.isRegularFile(file)) continue;
            lines.add(run.getFileName().toString() + " per frame:");
            for (var line : Files.readAllLines(file)) lines.add("  " + line);
        }
        return lines;
    }

    private static List<Properties> metrics(Collection<Capture> captures, String spot, String renderer)
            throws IOException {
        var result = new ArrayList<Properties>();
        for (var capture : captures)
            if (capture.spot().equals(spot) && capture.renderer().equals(renderer))
                result.add(read(capture.directory()));
        return result;
    }

    private static Properties read(Path capture) throws IOException {
        var properties = new Properties();
        try (var reader = Files.newBufferedReader(capture.resolve("summary.properties"))) {
            properties.load(reader);
        }
        if (!"COMPLETE".equals(properties.getProperty("status"))
                || Long.parseLong(properties.getProperty("frames", "0")) <= 0)
            throw new IllegalArgumentException("Compare only complete nonempty recordings: " + capture);
        return properties;
    }

    private static String fps(List<Properties> runs) {
        if (runs.isEmpty()) return "-";
        double median = median(runs, "average_fps");
        if (runs.size() == 1) return String.format(Locale.ROOT, "%.1f", median);
        var values = runs.stream()
                .mapToDouble(run -> Double.parseDouble(run.getProperty("average_fps")))
                .sorted()
                .toArray();
        return String.format(Locale.ROOT, "%.1f (%.1f-%.1f)", median, values[0], values[values.length - 1]);
    }

    private static String p99(List<Properties> runs) {
        return runs.isEmpty() ? "-" : String.format(Locale.ROOT, "%.2f", median(runs, "p99_ns") / 1e6);
    }

    private static double median(List<Properties> runs, String key) {
        var values = runs.stream()
                .mapToDouble(run -> Double.parseDouble(run.getProperty(key)))
                .sorted()
                .toArray();
        int middle = values.length / 2;
        return values.length % 2 == 1 ? values[middle] : (values[middle - 1] + values[middle]) / 2;
    }
}
