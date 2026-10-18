package dev.julia.audit.scan;

import dev.julia.audit.model.ArchiveAudit;
import dev.julia.audit.model.GraphicsReference;
import dev.julia.utils.io.ClassArchive;
import dev.julia.utils.io.Sha256;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;

public final class GraphicsArchiveScanner {
    /** Embedded LWJGL/LWJGLX implementations are binding internals, not graphics consumers. */
    public static boolean isBindingClass(String entry) {
        return entry.startsWith("org/lwjgl/") || entry.startsWith("org/lwjglx/");
    }

    public ArchiveAudit scan(Path path) throws IOException {
        var references = new ArrayList<GraphicsReference>();
        var scanner = new GraphicsClassScanner();
        int[] scanned = {0};
        int[] excluded = {0};
        ClassArchive.forEachClass(path, (entry, bytes) -> {
            if (isBindingClass(entry)) {
                excluded[0]++;
                return;
            }
            try {
                references.addAll(scanner.scan(bytes));
                scanned[0]++;
            } catch (IllegalArgumentException exception) {
                throw new IOException("Cannot inspect " + path + "!" + entry, exception);
            }
        });
        return new ArchiveAudit(path.getFileName().toString(), Sha256.of(path), scanned[0], excluded[0], references);
    }
}
