package dev.fopwoc.julia.profiling.core;

import java.time.Duration;

/** Timing plan independent of the game, renderer, agent, or scenario-control mechanism. */
public record CapturePlan(String scenario, Duration warmup, Duration duration) {
    public CapturePlan {
        if (scenario == null || !scenario.matches("[A-Za-z0-9_.-]{1,64}"))
            throw new IllegalArgumentException("Scenario must be a short file-safe name");
        if (warmup == null || warmup.isNegative() || duration == null || duration.isNegative() || duration.isZero())
            throw new IllegalArgumentException("Nonnegative warmup and positive capture duration required");
        warmup.toNanos();
        duration.toNanos();
    }
}
