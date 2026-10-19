package dev.fopwoc.julia.backend.compat;

import dev.fopwoc.julia.backend.BackendInternals;

/** Uniform pushes belong to a command-buffer recording, even when SDL recycles its native address. */
public final class CommandBufferState {
    private CommandBufferState() {}

    public static void reacquired() {
        var context = BackendInternals.context();
        context.lastPushedCbVs = 0;
        context.lastPushedCbFs = 0;
    }
}
