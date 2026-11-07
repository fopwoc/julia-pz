package dev.fopwoc.julia.profiling.run;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/** One launch of the game in a throwaway profile. Building it touches nothing; {@link #prepare} writes only inside the run. */
record RunPlan(
        String renderer,
        Path run,
        Path template,
        Path buddyHome,
        Scene scene,
        GameInstall game,
        List<String> command,
        List<ModCatalog.Mod> mods) {
    // Whichever copy a template carries: this agent decides whether Julia runs, not the mod list.
    private static final List<String> JULIA_IDS = List.of("Julia", "JuliaDev");
    private static final String STEAM = "-Dzomboid.steam=";

    static RunPlan of(
            String label,
            String renderer,
            Path run,
            Path template,
            Path agent,
            Path buddy,
            Path buddyHome,
            Scene scene,
            GameInstall game,
            List<String> diagnostics,
            List<ModCatalog.Mod> mods) {
        var command = new ArrayList<String>();
        command.add(game.java().toString());
        for (var option : game.options()) if (!option.startsWith(STEAM)) command.add(option);
        // Without Steam there's no Workshop folder and no client integration to vary between runs.
        command.add(STEAM + (scene.steam() ? "1" : "0"));
        // Later options win, so a scene can raise the heap above the game's default.
        command.addAll(scene.jvmOptions());
        // This agent goes first: Julia must arm before ZombieBuddy, and the observer refuses to install after its
        // targets load.
        command.add("-javaagent:" + agent + "=" + renderer + "," + run);
        if (buddy != null) {
            command.add("-Duser.home=" + run.resolve("home"));
            command.add("-javaagent:" + buddy);
        }
        command.add("-Dprofile.label=" + label);
        command.addAll(diagnostics);
        command.add("-cp");
        command.add(String.join(java.io.File.pathSeparator, game.classPath()));
        command.add(game.mainClass());
        command.add("-cachedir=" + run.resolve("profile"));
        return new RunPlan(
                renderer, run, template, buddy == null ? null : buddyHome, scene, game, List.copyOf(command), mods);
    }

    void prepare() throws IOException {
        var profile = run.resolve("profile");
        Bake.copyTree(template, profile);
        var saveMods = profile.resolve("Saves").resolve(scene.save()).resolve("mods.txt");
        if (scene.modsListed()) ActiveMods.set(saveMods, scene.mods());
        ActiveMods.remove(profile.resolve("mods/default.txt"), JULIA_IDS);
        ActiveMods.remove(saveMods, JULIA_IDS);
        // The default list applies from startup, before the save loads. Left alone, mods the save doesn't use would
        // still
        // run: a GL 4.3 mod crashes the native baseline on macOS while it's still at the menu.
        if (Files.isRegularFile(saveMods)) {
            Files.createDirectories(profile.resolve("mods"));
            Files.copy(saveMods, profile.resolve("mods/default.txt"), StandardCopyOption.REPLACE_EXISTING);
        }
        var options = profile.resolve("options.ini");
        var settings = Files.exists(options) ? Files.readString(options) : "";
        // The batch has no input focus, and background pausing would measure an idle simulation instead of the scene.
        settings = setOption(settings, "focusloss", "false");
        // Capped results don't show throughput. The automation also lifts the game's own limit once it's running.
        settings = setOption(settings, "vsync", "false");
        Files.writeString(options, settings);
        isolateBuddy();
    }

    private static String setOption(String settings, String key, String value) {
        var line = Pattern.compile("(?m)^" + key + "=.*$").matcher(settings);
        return line.find()
                ? line.replaceAll(key + "=" + value)
                : settings.stripTrailing() + "\n" + key + "=" + value + "\n";
    }

    /**
     * ZombieBuddy keeps its preload list in the user's home and ignores -cachedir. Without a home of its own, a baseline
     * would still load an installed Julia mod, and every run would rewrite the player's real list.
     */
    private void isolateBuddy() throws IOException {
        if (buddyHome == null) return;
        var home = run.resolve("home/.zombie_buddy");
        if (Files.isDirectory(buddyHome)) Bake.copyTree(buddyHome, home);
        else Files.createDirectories(home);
        var config = home.resolve("config.json");
        var text = Files.exists(config) ? Files.readString(config) : "{}";
        // Rewritten by hand so unknown settings survive exactly as ZombieBuddy wrote them.
        var cleared = clearPreload(text);
        Files.writeString(config, cleared);
        approveMods(home);
    }

    /**
     * A batch can't click ZombieBuddy's dialog, so the run's own copy approves exactly the Java mods the scene names, at the
     * hash installed now. The player's real approvals are left alone, and a changed mod still needs nothing else.
     */
    private void approveMods(Path home) throws IOException {
        var entries = new ArrayList<Object>();
        for (var mod : mods) {
            if (mod.jar() == null) continue;
            entries.add(Map.of("id", mod.id(), "jar_hash", sha256(mod.jar()), "decision", true));
        }
        if (entries.isEmpty()) return;
        var approvals = new LinkedHashMap<String, Object>();
        approvals.put("formatVersion", 2);
        approvals.put("mods", entries);
        Files.writeString(home.resolve("mod_approvals.json"), Json.write(approvals));
    }

    private static String sha256(Path file) throws IOException {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)));
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException(failure);
        }
    }

    static String clearPreload(String config) {
        var start = config.indexOf("\"preload_mods\"");
        if (start < 0) return config;
        int open = config.indexOf('{', start);
        int depth = 0;
        boolean quoted = false;
        for (int at = open; at < config.length(); at++) {
            char next = config.charAt(at);
            if (next == '\\' && quoted) at++;
            else if (next == '"') quoted = !quoted;
            else if (!quoted && next == '{') depth++;
            else if (!quoted && next == '}' && --depth == 0)
                return config.substring(0, open) + "{}" + config.substring(at + 1);
        }
        throw new IllegalArgumentException("Unreadable ZombieBuddy settings");
    }
}
