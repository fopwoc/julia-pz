package dev.julia.utils.io;

import java.io.IOException;
import java.io.Writer;

/** Tab-separated report rows with reversible escaping of separators and backslashes. */
public final class Tsv {
    private Tsv() {}

    public static String escape(String cell) {
        return cell.replace("\\", "\\\\").replace("\t", "\\t").replace("\r", "\\r").replace("\n", "\\n");
    }

    public static void row(Writer output, Object... cells) throws IOException {
        for (int index = 0; index < cells.length; index++) {
            if (index > 0) output.write('\t');
            output.write(escape(String.valueOf(cells[index])));
        }
        output.write('\n');
    }
}
