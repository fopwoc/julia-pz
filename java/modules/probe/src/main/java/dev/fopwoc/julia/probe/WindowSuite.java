package dev.fopwoc.julia.probe;

import dev.fopwoc.julia.probe.device.OffscreenProbe;
import dev.fopwoc.julia.probe.window.WindowProbe;
import dev.fopwoc.julia.runtime.ForeignWindow;
import java.nio.file.Path;

/** Device baseline and borrowed-window presentation, within one isolated native runtime. */
public final class WindowSuite {
    private WindowSuite() {}

    public static void run(Path output, ForeignWindow window) throws Exception {
        OffscreenProbe.run(output);
        WindowProbe.run(output.resolve("window"), window);
    }
}
