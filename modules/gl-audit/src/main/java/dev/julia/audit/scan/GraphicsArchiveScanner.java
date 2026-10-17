package dev.julia.audit.scan;

import dev.julia.audit.model.ArchiveAudit;
import dev.julia.audit.model.GraphicsReference;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.jar.JarFile;

public final class GraphicsArchiveScanner {
    public ArchiveAudit scan(Path path) throws IOException {
        var references = new ArrayList<GraphicsReference>();
        var scanner = new GraphicsClassScanner();
        int scanned = 0;
        int excluded = 0;
        // Select the same multi-release class variants that the running JDK would load.
        try (var jar = new JarFile(path.toFile(), true, JarFile.OPEN_READ, Runtime.version())) {
            for (var entry : jar.versionedStream().toList()) {
                String name = entry.getName();
                if (entry.isDirectory() || !name.endsWith(".class") || name.endsWith("module-info.class")) continue;
                if (name.startsWith("org/lwjgl/") || name.startsWith("org/lwjglx/")) {
                    excluded++;
                    continue;
                }
                try (var input = jar.getInputStream(entry)) {
                    references.addAll(scanner.scan(input.readAllBytes()));
                    scanned++;
                } catch (IllegalArgumentException exception) {
                    throw new IOException("Cannot inspect " + path + "!" + name, exception);
                }
            }
        }
        return new ArchiveAudit(path.getFileName().toString(), sha256(path), scanned, excluded, references);
    }

    private static String sha256(Path path) throws IOException {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            try (var input = new DigestInputStream(Files.newInputStream(path), digest)) {
                input.transferTo(java.io.OutputStream.nullOutputStream());
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException exception) {
            throw new AssertionError("JDK must provide SHA-256", exception);
        }
    }
}
