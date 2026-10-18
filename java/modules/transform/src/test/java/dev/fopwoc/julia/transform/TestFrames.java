package dev.fopwoc.julia.transform;

import dev.fopwoc.julia.runtime.RenderSession;

/** Frame callbacks for sessions whose tests exercise dispatch rather than frame lifecycle. */
public final class TestFrames {
    private TestFrames() {}

    public static RenderSession.Frames idle() {
        return new RenderSession.Frames() {
            @Override
            public void begin() {}

            @Override
            public void present() {}

            @Override
            public void abort() {}

            @Override
            public void close() {}
        };
    }
}
