package dev.fopwoc.julia.utils.io;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** Lowercase hexadecimal SHA-256 identities for archives and class bytes. */
public final class Sha256 {
    private Sha256() {}

    public static String of(byte[] bytes) {
        return HexFormat.of().formatHex(digest().digest(bytes));
    }

    public static String of(Path file) throws IOException {
        var digest = digest();
        try (var input = new DigestInputStream(Files.newInputStream(file), digest)) {
            input.transferTo(OutputStream.nullOutputStream());
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static MessageDigest digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException failure) {
            throw new AssertionError("The JDK must provide SHA-256", failure);
        }
    }
}
