package dev.julia.probe;

import dev.julia.probe.adapter.AdapterProbe;
import dev.julia.probe.render.OffscreenProbe;
import java.nio.file.Path;

/** Runs the device baseline followed by the full graphics adapter within one isolated native runtime. */
public final class ProbeSuite {
    private ProbeSuite() { }

    public static void run(Path output) throws Exception {
        OffscreenProbe.run(output);
        AdapterProbe.run(output.resolve("adapter"));
    }
}
