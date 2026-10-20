package dev.fopwoc.julia.backend.compat;

import dev.fopwoc.julia.backend.Upstream;

/** Uniform pushes belong to a command-buffer recording, even when SDL recycles its native address. */
public final class CommandBufferState {
    private CommandBufferState() {}

    public static void reacquired() {
        var context = Upstream.context();
        context.lastPushedCbVs = 0;
        context.lastPushedCbFs = 0;
    }
}
