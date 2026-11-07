package dev.fopwoc.julia.profiling.run;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;

/** Edits PZ's ActiveMods files (default.txt, a save's mods.txt), which keep separate mods and maps blocks. */
final class ActiveMods {
    private static final Pattern MODS = Pattern.compile("\\bmods\\s*\\{([^{}]*)\\}");

    private ActiveMods() {}

    static void remove(Path file, List<String> ids) throws IOException {
        if (!Files.exists(file)) return;
        var text = Files.readString(file);
        var match = MODS.matcher(text);
        if (!match.find()) throw new IllegalArgumentException("Unrecognized ActiveMods file: " + file);
        var block = match.group(1);
        for (var id : ids)
            block = block.replaceAll("[ \\t]*\\bmod\\s*=\\s*" + Pattern.quote(id) + "\\s*,?[ \\t]*\\r?\\n", "");
        Files.writeString(file, text.substring(0, match.start(1)) + block + text.substring(match.end(1)));
    }
}
