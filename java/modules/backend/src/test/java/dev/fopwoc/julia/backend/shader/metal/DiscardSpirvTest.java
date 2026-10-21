package dev.fopwoc.julia.backend.shader.metal;

import static org.junit.jupiter.api.Assertions.*;
import static org.lwjgl.util.spvc.Spv.*;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

class DiscardSpirvTest {
    @Test
    void guardsEveryEntryReturnAndPropagatesHelperReadsToDiscardingCallees() {
        var source = module(false, true);
        byte[] original = new byte[source.remaining()];
        source.duplicate().get(original);
        var translated = DiscardSpirv.translate(source);
        var code = instructions(translated);
        int helper = code.stream()
                .filter(i ->
                        i[0] == SpvOpDecorate && i[2] == SpvDecorationBuiltIn && i[3] == SpvBuiltInHelperInvocation)
                .findFirst()
                .orElseThrow()[1];
        assertTrue(Arrays.stream(code.stream()
                        .filter(i -> i[0] == SpvOpEntryPoint)
                        .findFirst()
                        .orElseThrow())
                .anyMatch(i -> i == helper));
        assertEquals(
                2, code.stream().filter(i -> i[0] == SpvOpBranchConditional).count());
        int function = 0, guards = 0;
        for (int at = 0; at < code.size(); at++) {
            int[] i = code.get(at);
            if (i[0] == SpvOpFunction) function = i[2];
            if (i[0] == SpvOpKill && function == 6) {
                int[] read = code.get(at - 1);
                assertEquals(SpvOpLoad, read[0]);
                assertEquals(helper, read[3]);
            }
            if (i[0] == SpvOpReturn && function == 10) {
                assertEquals(SpvOpLabel, code.get(at - 1)[0]);
                assertEquals(SpvOpKill, code.get(at - 2)[0]);
                guards++;
            }
        }
        assertEquals(2, guards);
        assertEquals(8, source.position());
        byte[] after = new byte[source.remaining()];
        source.duplicate().get(after);
        assertArrayEquals(original, after);
        assertTrue(translated.order(ByteOrder.LITTLE_ENDIAN).getInt(12) > 20);
    }

    @Test
    void reusesExistingHelperInputAndBooleanType() {
        var source = module(true, true);
        var before = instructions(source);
        var after = instructions(DiscardSpirv.translate(source));
        assertEquals(
                1,
                after.stream()
                        .filter(i -> i[0] == SpvOpDecorate
                                && i[2] == SpvDecorationBuiltIn
                                && i[3] == SpvBuiltInHelperInvocation)
                        .count());
        assertEquals(1, after.stream().filter(i -> i[0] == SpvOpTypeBool).count());
        assertEquals(1, after.stream().filter(i -> i[0] == SpvOpTypePointer).count());
        assertArrayEquals(
                before.stream().filter(i -> i[0] == SpvOpEntryPoint).findFirst().orElseThrow(),
                after.stream().filter(i -> i[0] == SpvOpEntryPoint).findFirst().orElseThrow());
        assertTrue(after.stream().anyMatch(i -> i[0] == SpvOpLoad && i[3] == 5));
    }

    @Test
    void skipsModulesWithoutDiscardsAndRejectsMalformedInstructions() {
        var source = module(false, false);
        assertSame(source, DiscardSpirv.translate(source));
        var broken = ByteBuffer.allocateDirect(24).order(ByteOrder.LITTLE_ENDIAN);
        broken.asIntBuffer().put(new int[] {SpvMagicNumber, 0x10000, 0, 20, 0, 0});
        assertThrows(IllegalArgumentException.class, () -> DiscardSpirv.translate(broken));
    }

    private static ByteBuffer module(boolean existing, boolean killed) {
        var words = new ArrayList<Integer>(List.of(SpvMagicNumber, 0x10000, 0, 20, 0));
        op(words, SpvOpCapability, SpvCapabilityShader);
        op(words, SpvOpMemoryModel, SpvAddressingModelLogical, SpvMemoryModelGLSL450);
        if (existing) op(words, SpvOpEntryPoint, SpvExecutionModelFragment, 10, 0x6e69616d, 0, 5);
        else op(words, SpvOpEntryPoint, SpvExecutionModelFragment, 10, 0x6e69616d, 0);
        op(words, SpvOpExecutionMode, 10, SpvExecutionModeOriginUpperLeft);
        if (existing) op(words, SpvOpDecorate, 5, SpvDecorationBuiltIn, SpvBuiltInHelperInvocation);
        op(words, SpvOpTypeVoid, 1);
        if (existing) {
            op(words, SpvOpTypeBool, 2);
            op(words, SpvOpTypePointer, 3, SpvStorageClassInput, 2);
        }
        op(words, SpvOpTypeFunction, 4, 1);
        if (existing) op(words, SpvOpVariable, 3, 5, SpvStorageClassInput);
        op(words, SpvOpFunction, 1, 6, 0, 4);
        op(words, SpvOpLabel, 7);
        op(words, killed ? SpvOpKill : SpvOpReturn);
        op(words, SpvOpFunctionEnd);
        op(words, SpvOpFunction, 1, 10, 0, 4);
        op(words, SpvOpLabel, 11);
        op(words, SpvOpFunctionCall, 1, 12, 6);
        op(words, SpvOpReturn);
        op(words, SpvOpLabel, 13);
        op(words, SpvOpReturn);
        op(words, SpvOpFunctionEnd);
        var result = ByteBuffer.allocateDirect((words.size() + 2) * 4).order(ByteOrder.LITTLE_ENDIAN);
        result.position(8);
        for (int word : words) result.putInt(word);
        return result.flip().position(8);
    }

    private static List<int[]> instructions(ByteBuffer module) {
        var input = module.duplicate().order(ByteOrder.LITTLE_ENDIAN).asIntBuffer();
        input.position(5);
        var result = new ArrayList<int[]>();
        while (input.hasRemaining()) {
            int word = input.get();
            int[] i = new int[word >>> 16];
            i[0] = word & 65535;
            for (int at = 1; at < i.length; at++) i[at] = input.get();
            result.add(i);
        }
        return result;
    }

    private static void op(List<Integer> words, int op, int... args) {
        words.add(((args.length + 1) << 16) | op);
        for (int arg : args) words.add(arg);
    }
}
