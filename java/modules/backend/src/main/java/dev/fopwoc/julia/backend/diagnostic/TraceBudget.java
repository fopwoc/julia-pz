package dev.fopwoc.julia.backend.diagnostic;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** Bounds recorded states, per-program repetition, and encoded output independently. */
final class TraceBudget {
    private final int states, perProgram, bytes;
    private final Map<Integer, Set<String>> seen = new HashMap<>();
    private int recorded, written;

    TraceBudget(int states, int perProgram, int bytes) {
        if (states <= 0 || perProgram <= 0 || bytes <= 0) throw new IllegalArgumentException("Nonpositive trace limit");
        this.states = states;
        this.perProgram = perProgram;
        this.bytes = bytes;
    }

    boolean available(int program) {
        var keys = seen.get(program);
        return recorded < states && written < bytes && (keys == null || keys.size() < perProgram);
    }

    boolean reserve(int program, String key, int size) {
        if (size < 0 || !available(program) || size > bytes - written) return false;
        var keys = seen.computeIfAbsent(program, ignored -> new HashSet<>());
        if (!keys.add(key)) return false;
        recorded++;
        written += size;
        return true;
    }
}
