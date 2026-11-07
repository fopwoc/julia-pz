package dev.fopwoc.julia.profiling.run;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/** How the installed game launches itself, read from its own settings so batches match a normal launch. */
record GameInstall(Path workingDirectory, Path java, List<String> options, String mainClass, List<String> classPath) {
    private static final String APP = "Project Zomboid.app";

    /** Follows the game's Java unless a settings-free override is needed, such as running the batch from another JDK. */
    static GameInstall read(Path game, String architecture) throws IOException {
        var directory = game.toAbsolutePath().normalize();
        if (Files.isDirectory(directory.resolve(APP))) directory = directory.resolve(APP);
        GameInstall install;
        if (directory.getFileName().toString().endsWith(".app")) install = mac(directory, architecture);
        else if (Files.isRegularFile(directory.resolve("ProjectZomboid64.json"))) install = launcherJson(directory);
        else throw new IllegalArgumentException("Supply the installed game folder: " + game);
        if (!Files.isRegularFile(install.workingDirectory.resolve("projectzomboid.jar"))
                || !Files.isRegularFile(install.java))
            throw new IllegalArgumentException("Game archive or bundled Java is missing under " + directory);
        return install;
    }

    private static GameInstall mac(Path app, String architecture) throws IOException {
        var plist = Files.readString(app.resolve("Contents/Info.plist"));
        var options = new ArrayList<>(strings(plist, "JVMOptions"));
        var working = app.resolve("Contents/Java");
        options.add("-Djava.library.path=" + working);
        var matcher = Pattern.compile("<key>JVMMainClassName</key>\\s*<string>([^<]*)</string>")
                .matcher(plist);
        if (!matcher.find()) throw new IllegalArgumentException("Info.plist names no main class");
        var runtime = architecture.equals("aarch64") || architecture.equals("arm64") ? "jre-aarch64" : "jre-x86_64";
        var java = app.resolve("Contents/PlugIns").resolve(runtime).resolve("Contents/Home/bin/java");
        return new GameInstall(working, java, options, matcher.group(1), List.of(".", "projectzomboid.jar"));
    }

    @SuppressWarnings("unchecked")
    private static GameInstall launcherJson(Path game) throws IOException {
        var settings = (Map<String, Object>) Json.parse(Files.readString(game.resolve("ProjectZomboid64.json")));
        var options = new ArrayList<String>();
        for (var value : (List<Object>) settings.get("vmArgs")) options.add((String) value);
        var classPath = new ArrayList<String>();
        for (var value : (List<Object>) settings.get("classpath")) classPath.add((String) value);
        var main = ((String) settings.get("mainClass")).replace('/', '.');
        var java = game.resolve("jre64/bin")
                .resolve(System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java");
        return new GameInstall(game, java, options, main, classPath);
    }

    private static List<String> strings(String plist, String key) {
        var block = Pattern.compile("<key>" + key + "</key>\\s*<array>(.*?)</array>", Pattern.DOTALL)
                .matcher(plist);
        if (!block.find()) throw new IllegalArgumentException("Info.plist has no " + key);
        var result = new ArrayList<String>();
        var entry = Pattern.compile("<string>([^<]*)</string>").matcher(block.group(1));
        while (entry.find())
            result.add(entry.group(1).replace("&lt;", "<").replace("&gt;", ">").replace("&amp;", "&"));
        return result;
    }
}
