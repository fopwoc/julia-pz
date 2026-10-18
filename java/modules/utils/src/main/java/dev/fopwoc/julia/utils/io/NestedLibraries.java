package dev.fopwoc.julia.utils.io;

import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.jar.JarFile;

/** Extracts content-addressed libraries without publishing their graphics bindings into the application loader. */
public final class NestedLibraries {
    private NestedLibraries() {}

    public static URLClassLoader open(Path archive, Path cache, ClassLoader shared) throws IOException {
        Path directory = cache.resolve(Sha256.of(archive));
        Files.createDirectories(directory);
        var urls = new ArrayList<URL>();
        try (var jar = new JarFile(archive.toFile())) {
            for (var entry : jar.stream().toList()) {
                String name = entry.getName();
                if (!name.startsWith("lib/") || !name.endsWith(".jar")) continue;
                Path target = directory.resolve(Path.of(name).getFileName());
                if (!Files.isRegularFile(target)) {
                    Path partial = Files.createTempFile(directory, "extract-", ".tmp");
                    try (var input = jar.getInputStream(entry)) {
                        Files.copy(input, partial, StandardCopyOption.REPLACE_EXISTING);
                        Files.move(
                                partial, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                    } finally {
                        Files.deleteIfExists(partial);
                    }
                }
                urls.add(target.toUri().toURL());
            }
        }
        if (urls.isEmpty()) throw new IOException("Archive contains no runtime libraries");
        return new URLClassLoader(urls.toArray(URL[]::new), ClassLoader.getPlatformClassLoader()) {
            @Override
            protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                if (name.startsWith("dev.fopwoc.julia.runtime.")) return shared.loadClass(name);
                return super.loadClass(name, resolve);
            }
        };
    }
}
