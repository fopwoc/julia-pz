package dev.fopwoc.julia.zomboid;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;

/** Plain-text status files a tester reads after a game launch. */
final class Status {
    private Status() {}

    static void write(Path output, String line) throws IOException {
        Files.writeString(output.resolve("status.txt"), line + "\n");
    }

    static void failure(Path output, Throwable failure) throws IOException {
        try (var writer = new PrintWriter(Files.newBufferedWriter(output.resolve("failure.txt")))) {
            failure.printStackTrace(writer);
        }
        write(output, "FAIL: Julia probe; see failure.txt");
    }
}
