package dev.fopwoc.julia.backend.shader.metal;

import static org.lwjgl.util.spvc.Spv.*;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;

/**
 * Reasserts helper discards at the fragment entry point. Metal can otherwise commit depth for killed pixels when
 * later control flow obscures a helper's discard, even though their colour writes are correctly suppressed.
 */
final class DiscardSpirv {
    private DiscardSpirv() {}

    static ByteBuffer translate(ByteBuffer source) {
        var input = source.duplicate().order(ByteOrder.LITTLE_ENDIAN).asIntBuffer();
        int[] words = new int[input.remaining()];
        input.get(words);
        if (words.length < 5 || words[0] != SpvMagicNumber)
            throw new IllegalArgumentException("Invalid fragment SPIR-V");
        int bool = 0, main = 0, helper = 0;
        boolean discards = false;
        for (int at = 5; at < words.length; at += length(words, at)) {
            int op = words[at] & 65535;
            if (op == SpvOpTypeBool) bool = words[at + 1];
            if (op == SpvOpEntryPoint && words[at + 1] == SpvExecutionModelFragment) {
                if (main != 0) throw new UnsupportedOperationException("Multiple fragment entry points");
                main = words[at + 2];
            }
            if (op == SpvOpDecorate
                    && words[at + 2] == SpvDecorationBuiltIn
                    && words[at + 3] == SpvBuiltInHelperInvocation) helper = words[at + 1];
            discards |= kills(op);
        }
        if (!discards || main == 0) return source;
        int next = words[3];
        boolean createBool = bool == 0, createHelper = helper == 0;
        if (createBool) bool = next++;
        int pointer = createHelper ? next++ : 0;
        if (createHelper) helper = next++;
        var output = new ArrayList<Integer>(words.length + 64);
        for (int i = 0; i < 5; i++) output.add(words[i]);
        boolean decorated = false, declared = false, inMain = false;
        for (int at = 5; at < words.length; at += length(words, at)) {
            int op = words[at] & 65535, count = length(words, at);
            if (createHelper && !decorated && op >= SpvOpTypeVoid && op <= SpvOpTypeForwardPointer) {
                instruction(output, SpvOpDecorate, helper, SpvDecorationBuiltIn, SpvBuiltInHelperInvocation);
                decorated = true;
            }
            if (!declared && op == SpvOpFunction) {
                if (createBool) instruction(output, SpvOpTypeBool, bool);
                if (createHelper) {
                    instruction(output, SpvOpTypePointer, pointer, SpvStorageClassInput, bool);
                    instruction(output, SpvOpVariable, pointer, helper, SpvStorageClassInput);
                }
                declared = true;
            }
            if (op == SpvOpFunction) inMain = words[at + 2] == main;
            if (op == SpvOpReturn && inMain) {
                int condition = next++, killed = next++, merge = next++;
                instruction(output, SpvOpLoad, bool, condition, helper);
                instruction(output, SpvOpSelectionMerge, merge, SpvSelectionControlMaskNone);
                instruction(output, SpvOpBranchConditional, condition, killed, merge);
                instruction(output, SpvOpLabel, killed);
                instruction(output, SpvOpKill);
                instruction(output, SpvOpLabel, merge);
            }
            // SPIRV-Cross must see each callee read the builtin to pass its mutable helper status
            // through helper arguments; otherwise manual discard updates reference an undeclared variable.
            if (kills(op)) instruction(output, SpvOpLoad, bool, next++, helper);
            if (createHelper && op == SpvOpEntryPoint && words[at + 1] == SpvExecutionModelFragment) {
                output.add(((count + 1) << 16) | op);
                for (int i = 1; i < count; i++) output.add(words[at + i]);
                output.add(helper);
            } else {
                for (int i = 0; i < count; i++) output.add(words[at + i]);
            }
            if (op == SpvOpFunctionEnd) inMain = false;
        }
        output.set(3, next);
        var result = ByteBuffer.allocateDirect(output.size() * Integer.BYTES).order(ByteOrder.LITTLE_ENDIAN);
        for (int word : output) result.putInt(word);
        return result.flip();
    }

    private static boolean kills(int op) {
        return op == SpvOpKill || op == SpvOpTerminateInvocation;
    }

    private static int length(int[] words, int at) {
        int count = words[at] >>> 16;
        if (count == 0 || count > words.length - at)
            throw new IllegalArgumentException("Malformed fragment SPIR-V instruction");
        return count;
    }

    private static void instruction(ArrayList<Integer> output, int op, int... operands) {
        output.add(((operands.length + 1) << 16) | op);
        for (int operand : operands) output.add(operand);
    }
}
