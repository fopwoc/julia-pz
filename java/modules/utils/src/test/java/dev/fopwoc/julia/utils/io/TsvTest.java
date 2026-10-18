package dev.fopwoc.julia.utils.io;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.StringWriter;
import org.junit.jupiter.api.Test;

class TsvTest {
    @Test
    void escapesSeparatorsReversiblyAndStringifiesCells() throws Exception {
        var output = new StringWriter();
        Tsv.row(output, "a\tb", "c\\d", 3, "line\r\nbreak");
        assertEquals("a\\tb\tc\\\\d\t3\tline\\r\\nbreak\n", output.toString());
    }
}
