package dev.fopwoc.julia.utils.io;

import java.io.IOException;
import java.nio.file.Path;
import java.util.jar.JarFile;

/** Walks the class files a running JDK would load from a JAR, honoring multi-release variants. */
public final class ClassArchive {
    @FunctionalInterface
    public interface Visitor {
        void visit(String entry, byte[] bytes) throws IOException;
    }

    private ClassArchive() {}

    public static void forEachClass(Path archive, Visitor visitor) throws IOException {
        try (var jar = new JarFile(archive.toFile(), true, JarFile.OPEN_READ, Runtime.version())) {
            for (var entry : jar.versionedStream().toList()) {
                String name = entry.getName();
                if (entry.isDirectory() || !name.endsWith(".class") || name.endsWith("module-info.class")) continue;
                byte[] bytes;
                try (var input = jar.getInputStream(entry)) {
                    bytes = input.readAllBytes();
                }
                visitor.visit(name, bytes);
            }
        }
    }
}
