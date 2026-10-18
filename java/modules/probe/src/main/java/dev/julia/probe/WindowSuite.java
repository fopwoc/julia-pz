package dev.julia.probe;

import dev.julia.probe.device.OffscreenProbe;
import dev.julia.probe.window.WindowProbe;
import dev.julia.runtime.ForeignWindow;
import java.nio.file.Path;

/** Device baseline and borrowed-window presentation, within one isolated native runtime. */
public final class WindowSuite {
    private WindowSuite() {}

    public static void run(Path output, ForeignWindow window) throws Exception {
        OffscreenProbe.run(output);
        WindowProbe.run(output.resolve("window"), window);
    }
}
