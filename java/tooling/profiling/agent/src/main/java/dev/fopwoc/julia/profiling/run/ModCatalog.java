package dev.fopwoc.julia.profiling.run;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/** The installed mods a scene can name, from the Workshop and the local mods folder. */
final class ModCatalog {
    private static final Pattern ID = Pattern.compile("(?m)^id=(.+)$");
    private static final Pattern JAR = Pattern.compile("(?m)^javaJarFile=(.+)$");

    /** {@code jar} is null for a Lua-only mod. */
    record Mod(String id, boolean workshop, Path jar) {}

    private final Map<String, Mod> mods = new HashMap<>();

    /** Later folders don't replace earlier ones, matching the game's preference for Workshop copies. */
    static ModCatalog scan(Path workshop, Path localMods) throws IOException {
        var catalog = new ModCatalog();
        if (Files.isDirectory(workshop)) {
            try (DirectoryStream<Path> items = Files.newDirectoryStream(workshop)) {
                for (var item : items) catalog.addFolder(item.resolve("mods"), true);
            }
        }
        catalog.addFolder(localMods, false);
        return catalog;
    }

    private void addFolder(Path folder, boolean workshop) throws IOException {
        if (!Files.isDirectory(folder)) return;
        try (DirectoryStream<Path> mods = Files.newDirectoryStream(folder, Files::isDirectory)) {
            for (var mod : mods) add(mod, workshop);
        }
    }

    private void add(Path mod, boolean workshop) throws IOException {
        for (var version : List.of("42", "common", ".")) {
            var info = mod.resolve(version).resolve("mod.info");
            if (!Files.isRegularFile(info)) continue;
            var text = Files.readString(info);
            var id = ID.matcher(text);
            if (!id.find()) continue;
            var jar = JAR.matcher(text);
            mods.putIfAbsent(
                    id.group(1).strip(),
                    new Mod(
                            id.group(1).strip(),
                            workshop,
                            jar.find() ? jar(mod, jar.group(1).strip()) : null));
            return;
        }
    }

    /** The path is relative to a version folder, and mods keep mod.info and the jar in different ones. */
    private static Path jar(Path mod, String relative) {
        for (var version : List.of("42", "common", ".")) {
            var candidate = mod.resolve(version).resolve(relative).normalize();
            if (Files.isRegularFile(candidate)) return candidate;
        }
        throw new IllegalArgumentException("Can't find " + relative + " in " + mod);
    }

    List<Mod> resolve(List<String> ids) {
        var result = new ArrayList<Mod>();
        for (var id : ids) {
            var mod = mods.get(id);
            if (mod == null) throw new IllegalArgumentException("The scene names a mod that isn't installed: " + id);
            result.add(mod);
        }
        return result;
    }
}
