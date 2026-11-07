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

    static String summarize(String scene, Collection<Capture> captures) throws IOException {
        var lines = new ArrayList<String>();
        lines.add("# " + scene);
        lines.add("");
        lines.add("| Spot | Runs | OpenGL FPS | Julia FPS | Julia vs OpenGL | OpenGL p99 ms | Julia p99 ms |");
        lines.add("| --- | ---: | ---: | ---: | ---: | ---: | ---: |");
        int runs = 0;
        for (var spot : captures.stream().map(Capture::spot).distinct().toList()) {
            var opengl = metrics(captures, spot, "opengl");
            var julia = metrics(captures, spot, "julia");
            runs = Math.max(runs, Math.min(opengl.size(), julia.size()));
            double baseline = median(opengl, "average_fps"), candidate = median(julia, "average_fps");
            lines.add(String.format(
                    Locale.ROOT,
                    "| %s | %d | %s | %s | %+.1f%% | %.2f | %.2f |",
                    spot,
                    Math.min(opengl.size(), julia.size()),
                    fps(opengl, baseline),
                    fps(julia, candidate),
                    (candidate / baseline - 1) * 100,
                    median(opengl, "p99_ns") / 1e6,
                    median(julia, "p99_ns") / 1e6));
        }
        lines.add("");
        if (runs < 2) lines.add("One pair of runs: this can't separate a real difference from run-to-run noise.");
        else lines.add("FPS is the median with its range across runs. Trust a difference only beyond those ranges.");
        lines.add(
                "CPU wall timings only, not GPU timestamps. Check neither side is capped at the display's refresh rate.");
        return String.join("\n", lines) + "\n";
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

    private static List<Properties> metrics(Collection<Capture> captures, String spot, String renderer)
            throws IOException {
        var result = new ArrayList<Properties>();
        for (var capture : captures)
            if (capture.spot().equals(spot) && capture.renderer().equals(renderer))
                result.add(read(capture.directory()));
        if (result.isEmpty()) throw new IllegalArgumentException("No " + renderer + " capture for " + spot);
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

    private static String fps(List<Properties> runs, double median) {
        if (runs.size() == 1) return String.format(Locale.ROOT, "%.1f", median);
        var values = runs.stream()
                .mapToDouble(run -> Double.parseDouble(run.getProperty("average_fps")))
                .sorted()
                .toArray();
        return String.format(Locale.ROOT, "%.1f (%.1f-%.1f)", median, values[0], values[values.length - 1]);
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
