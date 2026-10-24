package dev.fopwoc.julia.transform.asm;

import java.util.function.Supplier;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

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

    /** Whether {@code instruction} invokes {@code owner.name}; a {@code null} descriptor matches every overload. */
    public static boolean isCall(AbstractInsnNode instruction, String owner, String name, String descriptor) {
        return instruction instanceof MethodInsnNode call
                && call.owner.equals(owner)
                && call.name.equals(name)
                && (descriptor == null || call.desc.equals(descriptor));
    }

    /**
     * Replaces every invoke of {@code owner.name descriptor} in {@code method} with a fresh {@code replacement}, and
     * returns how many were replaced so the caller can {@link #require} the pinned count.
     */
    public static int replaceCalls(
            MethodNode method,
            String owner,
            String name,
            String descriptor,
            Supplier<? extends AbstractInsnNode> replacement) {
        int replaced = 0;
        for (var instruction : method.instructions.toArray())
            if (isCall(instruction, owner, name, descriptor)) {
                method.instructions.set(instruction, replacement.get());
                replaced++;
            }
        return replaced;
    }

    /** Fails when an upstream release moved, duplicated or removed a pinned anchor. */
    public static void require(String anchor, int found, int expected) {
        if (found != expected)
            throw new IllegalStateException(
                    "Pinned upstream anchor changed: " + anchor + "; found " + found + ", expected " + expected);
    }
}
