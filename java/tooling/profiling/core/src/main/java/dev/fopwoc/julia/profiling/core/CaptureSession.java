package dev.fopwoc.julia.profiling.core;

import java.util.Arrays;

/** Preallocated capture with caller-supplied monotonic timestamps; no per-frame file IO or allocations. */
public final class CaptureSession {
    public enum State {
        WAITING,
        WARMUP,
        RECORDING,
        COMPLETE,
        STOPPED,
        OVERFLOW
    }

    private final CapturePlan plan;
    private final long[] elapsed, intervals, steps;
    private State state = State.WAITING;
    private long epoch, previous;
    private boolean primed;
    private int count;

    public CaptureSession(CapturePlan plan, int capacity) {
        if (capacity < 1) throw new IllegalArgumentException("Positive sample capacity required");
        this.plan = plan;
        elapsed = new long[capacity];
        intervals = new long[capacity];
        steps = new long[capacity];
    }

    public synchronized State state() {
        return state;
    }

    public synchronized boolean active() {
        return state.ordinal() <= State.RECORDING.ordinal();
    }

    public synchronized void stop() {
        if (active()) state = State.STOPPED;
    }

    public synchronized void frame(long now, long renderStepNs) {
        if (!active()) return;
        if (renderStepNs < 0) throw new IllegalArgumentException("Negative render duration");
        if (state == State.WAITING) {
            epoch = now;
            previous = now;
            state = State.WARMUP;
        }
        if (now < previous) throw new IllegalArgumentException("Frame timestamps moved backwards");
        long elapsedNs = now - epoch;
        if (elapsedNs < plan.warmup().toNanos()) {
            previous = now;
            return;
        }
        if (elapsedNs - plan.warmup().toNanos() >= plan.duration().toNanos()) {
            state = State.COMPLETE;
            return;
        }
        state = State.RECORDING;
        if (!primed) {
            primed = true;
            previous = now;
            return;
        }
        if (now == previous) throw new IllegalArgumentException("Repeated frame timestamp");
        if (count == intervals.length) {
            state = State.OVERFLOW;
            return;
        }
        elapsed[count] = elapsedNs - plan.warmup().toNanos();
        intervals[count] = now - previous;
        steps[count] = renderStepNs;
        count++;
        previous = now;
    }

    public synchronized CaptureResult result() {
        if (active()) throw new IllegalStateException("Capture is still active");
        return new CaptureResult(
                plan,
                state,
                Arrays.copyOf(elapsed, count),
                Arrays.copyOf(intervals, count),
                Arrays.copyOf(steps, count));
    }
}
