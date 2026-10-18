package dev.fopwoc.julia.audit.report;

import dev.fopwoc.julia.audit.model.ArchiveAudit;
import dev.fopwoc.julia.audit.model.GraphicsReference;
import dev.fopwoc.julia.utils.io.Tsv;
import java.io.IOException;
import java.io.Writer;
import java.util.Comparator;
import java.util.List;

public final class TsvAuditWriter {
    private static final Comparator<GraphicsReference> ORDER = Comparator.comparing(GraphicsReference::target)
            .thenComparing(GraphicsReference::callerClass)
            .thenComparing(GraphicsReference::callerMethod)
            .thenComparing(GraphicsReference::callerDescriptor)
            .thenComparing(GraphicsReference::kind);

    public void write(List<ArchiveAudit> audits, Writer output) throws IOException {
        Tsv.row(
                output,
                "archive",
                "sha256",
                "symbol_kind",
                "owner",
                "name",
                "descriptor",
                "reference_kind",
                "caller_class",
                "caller_method",
                "caller_descriptor");
        for (var audit : audits) {
            for (var reference : audit.references().stream().sorted(ORDER).toList()) {
                var target = reference.target();
                Tsv.row(
                        output,
                        audit.archive(),
                        audit.sha256(),
                        target.kind(),
                        target.owner(),
                        target.name(),
                        target.descriptor(),
                        reference.kind(),
                        reference.callerClass(),
                        reference.callerMethod(),
                        reference.callerDescriptor());
            }
        }
    }
}
