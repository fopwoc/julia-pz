package dev.julia.probe.redirect;

import dev.julia.audit.scan.GraphicsClassScanner;
import java.io.IOException;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.jar.JarFile;

/** Prepares every graphics consumer across the supplied archives, retaining only in-memory bytecode. */
public final class ArchivePreparation {
    private ArchivePreparation() { }

    public static ArchivePlan inspect(List<Path> inputs) throws IOException {
        var preparation = new RuntimePreparation();
        var graphics = new GraphicsClassScanner(symbol -> symbol.isGraphicsBinding() || RuntimeCalls.target(symbol.owner()));
        var entries = new ArrayList<ArchivePlan.Entry>();
        for (Path input : inputs) {
            try (var archive = new JarFile(input.toFile(), true, JarFile.OPEN_READ, Runtime.version())) {
                for (var entry : archive.versionedStream().toList()) {
                    if (entry.isDirectory() || !entry.getName().endsWith(".class") || entry.getName().endsWith("module-info.class")
                            || entry.getName().startsWith("org/lwjgl/") || entry.getName().startsWith("org/lwjglx/")) continue;
                    byte[] original;
                    try (var stream = archive.getInputStream(entry)) { original = stream.readAllBytes(); }
                    if (!entry.getName().equals(FrameBindings.RENDER_THREAD + ".class") && graphics.scan(original).isEmpty()) continue;
                    var prepared = preparation.prepare(original);
                    entries.add(new ArchivePlan.Entry(input.getFileName().toString(), digest(original), prepared));
                }
            }
        }
        return new ArchivePlan(entries);
    }

    private static String digest(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (NoSuchAlgorithmException failure) { throw new AssertionError("SHA-256 unavailable", failure); }
    }
}
