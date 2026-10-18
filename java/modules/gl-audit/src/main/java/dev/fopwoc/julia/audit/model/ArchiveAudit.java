package dev.fopwoc.julia.audit.model;

import java.util.List;

public record ArchiveAudit(
        String archive, String sha256, int classesScanned, int bindingsExcluded, List<GraphicsReference> references) {
    public ArchiveAudit {
        references = List.copyOf(references);
    }
}
