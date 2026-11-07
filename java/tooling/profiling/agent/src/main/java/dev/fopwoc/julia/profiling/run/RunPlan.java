package dev.fopwoc.julia.profiling.run;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/** One launch of the game in a throwaway profile. Building it touches nothing; {@link #prepare} writes only inside the run. */
record RunPlan(
        String renderer, Path run, Path template, Path buddyHome, Scene scene, GameInstall game, List<String> command) {
    // Whichever copy a template carries: this agent decides whether Julia runs, not the mod list.
    private static final List<String> JULIA_IDS = List.of("Julia", "JuliaDev");
    private static final Pattern FOCUS_LOSS = Pattern.compile("(?m)^focusloss=.*$");

    static RunPlan of(
            String renderer,
            Path run,
            Path template,
            Path agent,
            Path buddy,
            Path buddyHome,
            Scene scene,
            GameInstall game) {
        var command = new ArrayList<String>();
        command.add(game.java().toString());
        command.addAll(game.options());
        // Later options win, so a scene can raise the heap above the game's default.
        command.addAll(scene.jvmOptions());
        // This agent goes first: Julia must arm before ZombieBuddy, and the observer refuses to install after its
        // targets load.
        command.add("-javaagent:" + agent + "=" + renderer + "," + run);
        if (buddy != null) {
            command.add("-Duser.home=" + run.resolve("home"));
            command.add("-javaagent:" + buddy);
        }
        command.add("-cp");
        command.add(String.join(java.io.File.pathSeparator, game.classPath()));
        command.add(game.mainClass());
        command.add("-cachedir=" + run.resolve("profile"));
        return new RunPlan(
                renderer, run, template, buddy == null ? null : buddyHome, scene, game, List.copyOf(command));
    }

    void prepare() throws IOException {
        var profile = run.resolve("profile");
        Bake.copyTree(template, profile);
        ActiveMods.remove(profile.resolve("mods/default.txt"), JULIA_IDS);
        ActiveMods.remove(profile.resolve("Saves").resolve(scene.save()).resolve("mods.txt"), JULIA_IDS);
        // The batch has no input focus, and background pausing would measure an idle simulation instead of the scene.
        var options = profile.resolve("options.ini");
        var settings = Files.exists(options) ? Files.readString(options) : "";
        var matcher = FOCUS_LOSS.matcher(settings);
        settings = matcher.find()
                ? matcher.replaceAll("focusloss=false")
                : settings.stripTrailing() + "\nfocusloss=false\n";
        Files.writeString(options, settings);
        isolateBuddy();
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
