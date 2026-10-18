package dev.julia.agent.audit;

import dev.julia.audit.model.ArchiveAudit;
import dev.julia.audit.model.GraphicsReference;
import dev.julia.audit.report.TsvAuditWriter;
import dev.julia.audit.scan.GraphicsClassScanner;
import java.io.BufferedWriter;
import java.io.IOException;
import java.lang.instrument.ClassFileTransformer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.ProtectionDomain;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

/** Records graphics references of {@code zombie/}, {@code viewpoint/}, and {@code imgui/} classes as they load. */
public final class RuntimeAudit implements ClassFileTransformer, AutoCloseable {
    private static final System.Logger LOG = System.getLogger(RuntimeAudit.class.getName());
    private final GraphicsClassScanner scanner = new GraphicsClassScanner();
    private final ConcurrentLinkedQueue<GraphicsReference> references = new ConcurrentLinkedQueue<>();
    private final AtomicInteger scanned = new AtomicInteger();
    private final AtomicInteger failures = new AtomicInteger();
    private final BufferedWriter output;
    private boolean closed;

    public RuntimeAudit(Path destination) throws IOException {
        Path path = destination.toAbsolutePath().normalize();
        Files.createDirectories(path.getParent());
        // Reserve the destination before startup; never overwrite an existing file.
        output = Files.newBufferedWriter(path, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
    }

    @Override
    public byte[] transform(ClassLoader loader, String className, Class<?> redefined,
                            ProtectionDomain domain, byte[] bytes) {
        if (className == null || !(className.startsWith("zombie/")
                || className.startsWith("viewpoint/") || className.startsWith("imgui/"))) return null;
        try {
            references.addAll(scanner.scan(bytes));
            scanned.incrementAndGet();
        } catch (RuntimeException exception) {
            failures.incrementAndGet();
            LOG.log(System.Logger.Level.ERROR, "Cannot audit " + className, exception);
        }
        return null;
    }

    @Override
    public synchronized void close() throws IOException {
        if (closed) return;
        closed = true;
        try (output) {
            // Loaded classes can originate from several archives; no archive hash is claimed.
            var report = new ArchiveAudit("runtime-loaded-classes", "", scanned.get(), 0, List.copyOf(references));
            new TsvAuditWriter().write(List.of(report), output);
        }
        if (failures.get() > 0) {
            LOG.log(System.Logger.Level.ERROR, "Runtime inventory is incomplete: {0} class audits failed", failures.get());
        }
        LOG.log(System.Logger.Level.INFO, "Wrote runtime inventory for {0} classes", scanned.get());
    }
}
