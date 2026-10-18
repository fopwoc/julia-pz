package dev.julia.zomboid;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** Locates the installed mod archive and Julia's output root, honoring explicit overrides for test launches. */
final class ModPaths {
    private static final String ARCHIVE = "Julia.jar";
    private static final List<String> INSTALLED = List.of("media/java/client/" + ARCHIVE, "42/media/java/client/" + ARCHIVE);

    private ModPaths() {}

    static Path archive() throws Exception {
        String explicit = System.getProperty("julia.modJar");
        if (explicit != null) return Path.of(explicit).toRealPath();
        var source = Main.class.getProtectionDomain().getCodeSource();
        if (source != null) {
            Path candidate = Path.of(source.getLocation().toURI());
            if (Files.isRegularFile(candidate) && candidate.getFileName().toString().equals(ARCHIVE)) return candidate;
        }
        Path directory = Path.of((String) fileSystem().getMethod("getModDir", String.class).invoke(fileSystemInstance(), "Julia"));
        for (String relative : INSTALLED) {
            Path candidate = directory.resolve(relative);
            if (Files.isRegularFile(candidate)) return candidate.toRealPath();
        }
        throw new IOException("Cannot locate " + ARCHIVE + "; supply -Djulia.modJar=/absolute/path/" + ARCHIVE);
    }

    static Path outputRoot() throws Exception {
        String explicit = System.getProperty("julia.outputDir");
        if (explicit != null) return Path.of(explicit).toAbsolutePath();
        return Path.of((String) fileSystem().getMethod("getCacheDir").invoke(fileSystemInstance()), "julia");
    }

    private static Class<?> fileSystem() throws ClassNotFoundException {
        return Class.forName("zombie.ZomboidFileSystem");
    }

    private static Object fileSystemInstance() throws ReflectiveOperationException {
        return fileSystem().getField("instance").get(null);
    }
}
