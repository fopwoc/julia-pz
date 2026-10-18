package dev.fopwoc.julia.transform.prepare;

import java.util.List;

/** A checked candidate, never executable through this API while any blocker remains. */
public final class PreparedClass {
    private final String name;
    private final byte[] bytecode;
    private final List<String> blockers;
    private final int calls;
    private final boolean frameHooks;

    PreparedClass(String name, byte[] bytecode, List<String> blockers, int calls, boolean frameHooks) {
        this.name = name;
        this.bytecode = bytecode.clone();
        this.blockers = List.copyOf(blockers);
        this.calls = calls;
        this.frameHooks = frameHooks;
    }

    public String name() {
        return name;
    }

    public List<String> blockers() {
        return blockers;
    }

    public int calls() {
        return calls;
    }

    public boolean frameHooks() {
        return frameHooks;
    }

    public boolean ready() {
        return blockers.isEmpty();
    }

    public byte[] requireBytecode() {
        if (!ready()) throw new IllegalStateException("Cannot activate " + name + ": " + blockers);
        return bytecode.clone();
    }
}
