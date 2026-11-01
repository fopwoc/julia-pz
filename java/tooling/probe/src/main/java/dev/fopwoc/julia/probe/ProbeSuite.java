package dev.fopwoc.julia.probe;

import dev.fopwoc.julia.probe.adapter.AdapterProbe;
import dev.fopwoc.julia.probe.device.OffscreenProbe;
import java.nio.file.Path;

/** Device baseline followed by the full graphics adapter, within one isolated native runtime. */
public final class ProbeSuite {
    private ProbeSuite() {}

    public static void run(Path output) throws Exception {
        OffscreenProbe.run(output);
        AdapterProbe.run(output.resolve("adapter"));
    }
}
