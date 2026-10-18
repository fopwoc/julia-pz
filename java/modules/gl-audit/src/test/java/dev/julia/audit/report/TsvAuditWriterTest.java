package dev.julia.audit.report;

import static org.junit.jupiter.api.Assertions.*;

import dev.julia.audit.model.ArchiveAudit;
import dev.julia.audit.model.GraphicsReference;
import dev.julia.audit.model.GraphicsSymbol;
import java.io.StringWriter;
import java.util.List;
import org.junit.jupiter.api.Test;

class TsvAuditWriterTest {
    @Test
    void sortsSymbolsAndEscapesCellSeparators() throws Exception {
        var audit = new ArchiveAudit("game\tname.jar", "hash", 1, 0, List.of(reference("z"), reference("a")));
        var output = new StringWriter();
        new TsvAuditWriter().write(List.of(audit), output);
        var lines = output.toString().lines().toList();
        assertEquals(3, lines.size());
        assertEquals(10, lines.get(1).split("\t", -1).length);
        assertEquals("game\\tname.jar", lines.get(1).split("\t")[0]);
        assertEquals("a", lines.get(1).split("\t")[4]);
        assertEquals("z", lines.get(2).split("\t")[4]);
    }

    private static GraphicsReference reference(String method) {
        return new GraphicsReference(
                new GraphicsSymbol(GraphicsSymbol.Kind.METHOD, "org/lwjgl/opengl/GL11", method, "()V"),
                "sample/Renderer",
                "render",
                "()V",
                GraphicsReference.Kind.INSTRUCTION);
    }
}
