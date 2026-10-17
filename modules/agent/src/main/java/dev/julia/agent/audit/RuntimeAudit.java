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
import java.util.logging.Level;
import java.util.logging.Logger;

public final class RuntimeAudit implements ClassFileTransformer, AutoCloseable {
    private static final Logger LOG = Logger.getLogger(RuntimeAudit.class.getName());
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
            LOG.log(Level.SEVERE, "Cannot audit " + className, exception);
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
        if (failures.get() > 0) LOG.severe("Runtime inventory is incomplete: " + failures.get() + " class audits failed");
        LOG.info("Wrote runtime inventory for " + scanned.get() + " classes");
    }
}
