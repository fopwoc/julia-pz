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

/** Prepares in-process graphics consumers across the supplied archives, retaining only in-memory bytecode. */
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
                // ZombieBuddy also bundles a standalone native-GL approval executable. It is not
                // a game consumer; the live transformer still rejects it if loaded in this JVM.
                if (entry.equals("me/zed_0xff/zombie_buddy/frontend/ImguiApprovalMain.class")
                        || entry.startsWith("me/zed_0xff/zombie_buddy/frontend/ImguiApprovalMain$")) return;
                if (!entry.equals(RENDER_THREAD_ENTRY) && graphics.scan(bytes).isEmpty()) return;
                entries.add(new ArchivePlan.Entry(archive, Sha256.of(bytes), preparation.prepare(bytes)));
            });
        }
        return new ArchivePlan(entries);
    }
}
