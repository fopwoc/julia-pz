package dev.fopwoc.julia.transform.prepare;

import dev.fopwoc.julia.audit.scan.GraphicsArchiveScanner;
import dev.fopwoc.julia.audit.scan.GraphicsClassScanner;
import dev.fopwoc.julia.transform.pass.FramePass;
import dev.fopwoc.julia.utils.io.ClassArchive;
import dev.fopwoc.julia.utils.io.Sha256;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Prepares every graphics consumer across the supplied archives, retaining only in-memory bytecode. */
public final class ArchivePreparation {
    private static final String RENDER_THREAD_ENTRY = FramePass.RENDER_THREAD + ".class";

    private ArchivePreparation() {}

    public static ArchivePlan inspect(List<Path> inputs) throws IOException {
        var preparation = new RuntimePreparation();
        var graphics = new GraphicsClassScanner(RuntimePreparation::isGraphicsConsumer);
        var entries = new ArrayList<ArchivePlan.Entry>();
        for (Path input : inputs) {
            String archive = input.getFileName().toString();
            ClassArchive.forEachClass(input, (entry, bytes) -> {
                if (GraphicsArchiveScanner.isBindingClass(entry)) return;
                if (!entry.equals(RENDER_THREAD_ENTRY) && graphics.scan(bytes).isEmpty()) return;
                entries.add(new ArchivePlan.Entry(archive, Sha256.of(bytes), preparation.prepare(bytes)));
            });
        }
        return new ArchivePlan(entries);
    }
}
