package dev.julia.probe;

import dev.julia.probe.render.OffscreenProbe;
import dev.julia.probe.window.WindowProbe;
import java.nio.file.Path;

/** Device baseline and borrowed-window presentation share one adapted/native runtime. */
public final class WindowSuite {
    private WindowSuite() { }

    public static void run(Path output, long cocoaWindow) throws Exception {
        OffscreenProbe.run(output);
        WindowProbe.run(output.resolve("window"), cocoaWindow);
    }
}
