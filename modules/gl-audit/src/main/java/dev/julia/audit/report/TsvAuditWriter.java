package dev.julia.audit.report;

import dev.julia.audit.model.ArchiveAudit;
import dev.julia.audit.model.GraphicsReference;
import java.io.IOException;
import java.io.Writer;
import java.util.Comparator;
import java.util.List;

public final class TsvAuditWriter {
    public void write(List<ArchiveAudit> audits, Writer output) throws IOException {
        output.write("archive\tsha256\tsymbol_kind\towner\tname\tdescriptor\treference_kind\tcaller_class\tcaller_method\tcaller_descriptor\n");
        var order = Comparator.comparing(GraphicsReference::target)
                .thenComparing(GraphicsReference::callerClass)
                .thenComparing(GraphicsReference::callerMethod)
                .thenComparing(GraphicsReference::callerDescriptor)
                .thenComparing(GraphicsReference::kind);
        for (var audit : audits) {
            for (var reference : audit.references().stream().sorted(order).toList()) {
                var target = reference.target();
                var row = List.of(audit.archive(), audit.sha256(), target.kind().name(), target.owner(),
                        target.name(), target.descriptor(), reference.kind().name(), reference.callerClass(),
                        reference.callerMethod(), reference.callerDescriptor());
                output.write(String.join("\t", row.stream().map(TsvAuditWriter::escape).toList()));
                output.write('\n');
            }
        }
    }

    private static String escape(String text) {
        return text.replace("\\", "\\\\").replace("\t", "\\t").replace("\r", "\\r").replace("\n", "\\n");
    }
}
