package dev.julia.probe.redirect;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/** Whole-input readiness gate: never exposes a partially supported archive as an activation plan. */
public final class ArchivePlan {
    public record Entry(String archive, String sha256, PreparedClass prepared) { }
    private final List<Entry> entries;
    private final Set<String> blockers;

    ArchivePlan(List<Entry> entries) {
        this.entries = List.copyOf(entries);
        var failures = new TreeSet<String>();
        var names = new java.util.HashSet<String>();
        int frames = 0;
        for (var entry : entries) {
            failures.addAll(entry.prepared().blockers());
            if (!names.add(entry.prepared().name())) failures.add("DUPLICATE_CLASS " + entry.prepared().name());
            if (entry.prepared().frameHooks()) frames++;
        }
        if (frames != 1) failures.add("FRAME_COUNT expected=1 actual=" + frames);
        blockers = java.util.Collections.unmodifiableSortedSet(failures);
    }

    public List<Entry> entries() { return entries; }
    public Set<String> blockers() { return blockers; }
    public boolean ready() { return blockers.isEmpty(); }

    public Map<String, byte[]> requireBytecode() {
        if (!ready()) throw new IllegalStateException("Archive activation blocked: " + blockers);
        var classes = new HashMap<String, byte[]>();
        for (var entry : entries) classes.put(entry.prepared().name(), entry.prepared().requireBytecode());
        return Map.copyOf(classes);
    }
}
