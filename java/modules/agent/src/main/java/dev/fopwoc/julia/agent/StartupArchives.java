package dev.fopwoc.julia.agent;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * The jars Julia must prepare before any game or mod class loads: the class path plus every other startup agent.
 *
 * <p>The JVM adds an agent's jar to the class path only when that agent starts. Julia's agent runs first, so on
 * launchers that list jars explicitly (Linux, Windows) ZombieBuddy's agent jar is not on the class path yet. The macOS
 * launcher happens to put every jar in its directory on the class path, which hid this.
 */
final class StartupArchives {
    private static final String AGENT = "-javaagent:";

    private StartupArchives() {}

    static List<Path> collect(String classPath, List<String> jvmArguments, Path self, Path workingDirectory) {
        var classPathJars = Stream.of(classPath.split(Pattern.quote(File.pathSeparator)))
                .filter(entry -> !entry.isEmpty())
                .map(Path::of);
        var agentJars = jvmArguments.stream()
                .filter(argument -> argument.startsWith(AGENT))
                .map(argument -> agentJar(argument.substring(AGENT.length())));
        var archives = new LinkedHashSet<Path>();
        Stream.concat(classPathJars, agentJars)
                .map(path -> workingDirectory.resolve(path).toAbsolutePath().normalize())
                .filter(path -> Files.isRegularFile(path) && path.toString().endsWith(".jar"))
                .filter(path -> !path.equals(self.toAbsolutePath().normalize()))
                .forEach(archives::add);
        return List.copyOf(archives);
    }

    /** {@code -javaagent:<jar>[=<options>]}; a jar path itself never contains {@code =} before {@code .jar}. */
    private static Path agentJar(String value) {
        int end = value.indexOf(".jar");
        return Path.of(end < 0 ? value : value.substring(0, end + ".jar".length()));
    }
}
