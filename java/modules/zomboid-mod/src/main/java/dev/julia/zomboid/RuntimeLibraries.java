package dev.julia.zomboid;

import dev.julia.utils.io.Sha256;
import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.jar.JarFile;

/**
 * The probe's nested libraries, extracted once per archive content into a shared cache and loaded apart from PZ.
 *
 * <p>Only the JDK-only {@code dev.julia.runtime} boundary resolves through the mod's own loader, so values such as a
 * {@code ForeignWindow} keep one identity on both sides.
 */
final class RuntimeLibraries {
    private static final String SHARED = "dev.julia.runtime.";

    private RuntimeLibraries() {}

    static URLClassLoader open(Path archive, Path cache) throws IOException {
        Path directory = cache.resolve(Sha256.of(archive));
        Files.createDirectories(directory);
        var urls = new ArrayList<URL>();
        try (var jar = new JarFile(archive.toFile())) {
            for (var entry : jar.stream().toList()) {
                String name = entry.getName();
                if (!name.startsWith("lib/") || !name.endsWith(".jar")) continue;
                Path target = directory.resolve(Path.of(name).getFileName());
                if (!Files.isRegularFile(target)) {
                    // Publish complete files only; concurrent game instances may share the cache.
                    Path partial = Files.createTempFile(directory, "extract-", ".tmp");
                    try (var input = jar.getInputStream(entry)) {
                        Files.copy(input, partial, StandardCopyOption.REPLACE_EXISTING);
                        Files.move(partial, target, StandardCopyOption.ATOMIC_MOVE);
                    } catch (FileAlreadyExistsException raced) {
                        Files.deleteIfExists(partial);
                    }
                }
                urls.add(target.toUri().toURL());
            }
        }
        if (urls.isEmpty()) throw new IOException(archive.getFileName() + " contains no probe libraries");
        return new SharedBoundaryLoader(urls.toArray(URL[]::new));
    }

    private static final class SharedBoundaryLoader extends URLClassLoader {
        static {
            registerAsParallelCapable();
        }

        SharedBoundaryLoader(URL[] urls) {
            super(urls, ClassLoader.getPlatformClassLoader());
        }

        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            if (name.startsWith(SHARED))
                return RuntimeLibraries.class.getClassLoader().loadClass(name);
            return super.loadClass(name, resolve);
        }
    }
}
