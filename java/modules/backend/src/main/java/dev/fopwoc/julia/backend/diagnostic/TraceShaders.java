package dev.fopwoc.julia.backend.diagnostic;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;

/** Keeps exact compiler inputs beside state snapshots without unbounded shader output. */
final class TraceShaders {
    private static final int LIMIT = 8 * 1024 * 1024;
    private final Path output;
    private final Map<String, String> names = new HashMap<>();
    private int written;

    TraceShaders(Path output) {
        this.output = output;
    }

    String write(String source) throws IOException {
        if (source == null || source.isEmpty()) return "none";
        String known = names.get(source);
        if (known != null) return known;
        byte[] bytes = source.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > LIMIT - written) return "omitted-size-limit";
        String name;
        try {
            name = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)) + ".glsl";
        } catch (NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
        }
        Files.createDirectories(output);
        Files.write(output.resolve(name), bytes);
        written += bytes.length;
        String relative = output.getFileName() + "/" + name;
        names.put(source, relative);
        return relative;
    }
}
