package dev.fopwoc.julia.transform.asm;

import org.objectweb.asm.tree.AbstractInsnNode;

/**
 * Navigation and verification for version-pinned bytecode anchors. Labels, line numbers and frames sit between real
 * instructions in ASM's tree, so pattern matching must step over them.
 */
public final class Anchors {
    private Anchors() {}

    /** The nearest real instruction before {@code instruction}, or {@code null}. */
    public static AbstractInsnNode previous(AbstractInsnNode instruction) {
        do instruction = instruction.getPrevious();
        while (instruction != null && instruction.getOpcode() < 0);
        return instruction;
    }

    /** The nearest real instruction after {@code instruction}, or {@code null}. */
    public static AbstractInsnNode next(AbstractInsnNode instruction) {
        do instruction = instruction.getNext();
        while (instruction != null && instruction.getOpcode() < 0);
        return instruction;
    }

    /** Fails when an upstream release moved, duplicated or removed a pinned anchor. */
    public static void require(String anchor, int found, int expected) {
        if (found != expected)
            throw new IllegalStateException(
                    "Pinned upstream anchor changed: " + anchor + "; found " + found + ", expected " + expected);
    }
}
