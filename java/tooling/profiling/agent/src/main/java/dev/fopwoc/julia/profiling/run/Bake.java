package dev.fopwoc.julia.profiling.run;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;

/** Prepares a reusable profile template from one existing save, leaving the source untouched. */
final class Bake {
    private Bake() {}

    static void bake(Path source, String save, Path output) throws IOException {
        var world = source.resolve("Saves").resolve(save);
        if (!Files.isRegularFile(world.resolve("map_ver.bin")))
            throw new IllegalArgumentException("No save at " + world);
        if (Files.exists(output)) throw new IllegalArgumentException("Delete the existing template first: " + output);
        copyTree(world, output.resolve("Saves").resolve(save));
        Files.createDirectories(output.resolve("mods"));
        copyIfPresent(source.resolve("options.ini"), output.resolve("options.ini"));
        // Mods keep their settings beside options.ini. Without them a mod can open its first-run setup, which nothing
        // in
        // a batch would answer.
        try (DirectoryStream<Path> settings = Files.newDirectoryStream(source, "*.properties")) {
            for (var file : settings) copyIfPresent(file, output.resolve(file.getFileName()));
        }
        copyIfPresent(source.resolve("mods/default.txt"), output.resolve("mods/default.txt"));
        // PZ resets the default mod list when the marker for the running game version is missing.
        if (Files.isDirectory(source.resolve("mods"))) {
            try (DirectoryStream<Path> markers = Files.newDirectoryStream(source.resolve("mods"), "reset-mods-*.txt")) {
                for (var marker : markers)
                    copyIfPresent(marker, output.resolve("mods").resolve(marker.getFileName()));
            }
        }
    }

    static void copyTree(Path source, Path target) throws IOException {
        try (var files = Files.walk(source)) {
            for (var file : (Iterable<Path>) files.sorted(Comparator.naturalOrder())::iterator) {
                var destination = target.resolve(source.relativize(file).toString());
                if (Files.isDirectory(file)) Files.createDirectories(destination);
                else {
                    Files.createDirectories(destination.getParent());
                    Files.copy(file, destination, StandardCopyOption.COPY_ATTRIBUTES);
                }
            }
        }
    }

    private static void copyIfPresent(Path source, Path target) throws IOException {
        if (Files.isRegularFile(source)) Files.copy(source, target, StandardCopyOption.COPY_ATTRIBUTES);
    }
}
