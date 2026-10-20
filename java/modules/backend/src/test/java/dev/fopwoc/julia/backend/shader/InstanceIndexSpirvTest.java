package dev.fopwoc.julia.backend.shader;

import static org.junit.jupiter.api.Assertions.*;
import static org.lwjgl.util.spvc.Spv.*;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.lwjgl.opengl.GL20;

class InstanceIndexSpirvTest {
    @Test
    void subtractsBaseFromInstanceLoadsWithoutChangingVertexIdsOrCallerMemory() {
        var source = module(false);
        byte[] original = new byte[source.remaining()];
        source.duplicate().get(original);
        int position = source.position(), limit = source.limit();
        var code = instructions(InstanceIndexSpirv.translate(source, GL20.GL_VERTEX_SHADER));
        int[] baseDecoration = code.stream()
                .filter(i -> i[0] == SpvOpDecorate && i[3] == SpvBuiltInBaseInstance)
                .findFirst()
                .orElseThrow();
        int base = baseDecoration[1];
        var entry =
                code.stream().filter(i -> i[0] == SpvOpEntryPoint).findFirst().orElseThrow();
        assertTrue(Arrays.stream(entry).anyMatch(i -> i == base));
        assertTrue(code.stream().anyMatch(i -> i[0] == SpvOpVariable && i[2] == base && i[3] == SpvStorageClassInput));
        int instanceLoad = code.stream()
                .filter(i -> i[0] == SpvOpLoad && i[3] == 5)
                .findFirst()
                .orElseThrow()[2];
        int baseLoad = code.stream()
                .filter(i -> i[0] == SpvOpLoad && i[3] == base)
                .findFirst()
                .orElseThrow()[2];
        assertNotEquals(6, instanceLoad);
        assertTrue(code.stream().anyMatch(i -> Arrays.equals(i, new int[] {SpvOpISub, 2, 6, instanceLoad, baseLoad})));
        assertTrue(code.stream().anyMatch(i -> Arrays.equals(i, new int[] {SpvOpLoad, 2, 10, 7})));
        assertEquals(position, source.position());
        assertEquals(limit, source.limit());
        byte[] after = new byte[source.remaining()];
        source.duplicate().get(after);
        assertArrayEquals(original, after);
    }

    @Test
    void reusesAnExistingUnsignedBaseInputAndPreservesItsBits() {
        var code = instructions(InstanceIndexSpirv.translate(module(true), GL20.GL_VERTEX_SHADER));
        assertEquals(
                1,
                code.stream()
                        .filter(i -> i[0] == SpvOpDecorate && i[3] == SpvBuiltInBaseInstance)
                        .count());
        int baseLoad = code.stream()
                .filter(i -> i[0] == SpvOpLoad && i[3] == 14)
                .findFirst()
                .orElseThrow()[2];
        int converted = code.stream()
                .filter(i -> i[0] == SpvOpBitcast && i[1] == 2 && i[3] == baseLoad)
                .findFirst()
                .orElseThrow()[2];
        assertTrue(code.stream().anyMatch(i -> i[0] == SpvOpISub && i[2] == 6 && i[4] == converted));
    }

    @Test
    void leavesOtherStagesAloneAndRejectsMalformedInstructions() {
        var source = module(false);
        assertSame(source, InstanceIndexSpirv.translate(source, GL20.GL_FRAGMENT_SHADER));
        var broken = ByteBuffer.allocateDirect(24).order(ByteOrder.LITTLE_ENDIAN);
        broken.asIntBuffer().put(new int[] {SpvMagicNumber, 0x10000, 0, 11, 0, 0});
        assertThrows(IllegalArgumentException.class, () -> InstanceIndexSpirv.translate(broken, GL20.GL_VERTEX_SHADER));
    }

    private static ByteBuffer module(boolean existing) {
        var words = new ArrayList<Integer>();
        words.addAll(java.util.List.of(SpvMagicNumber, 0x10000, 0, existing ? 15 : 11, 0));
        op(words, SpvOpCapability, SpvCapabilityShader);
        op(words, SpvOpMemoryModel, SpvAddressingModelLogical, SpvMemoryModelGLSL450);
        if (existing) op(words, SpvOpEntryPoint, SpvExecutionModelVertex, 8, 0x6e69616d, 0, 5, 7, 14);
        else op(words, SpvOpEntryPoint, SpvExecutionModelVertex, 8, 0x6e69616d, 0, 5, 7);
        op(words, SpvOpDecorate, 5, SpvDecorationBuiltIn, SpvBuiltInInstanceIndex);
        op(words, SpvOpDecorate, 7, SpvDecorationBuiltIn, SpvBuiltInVertexIndex);
        if (existing) op(words, SpvOpDecorate, 14, SpvDecorationBuiltIn, SpvBuiltInBaseInstance);
        op(words, SpvOpTypeVoid, 1);
        op(words, SpvOpTypeInt, 2, 32, 1);
        op(words, SpvOpTypePointer, 3, SpvStorageClassInput, 2);
        op(words, SpvOpTypeFunction, 4, 1);
        if (existing) {
            op(words, SpvOpTypeInt, 12, 32, 0);
            op(words, SpvOpTypePointer, 13, SpvStorageClassInput, 12);
        }
        op(words, SpvOpVariable, 3, 5, SpvStorageClassInput);
        op(words, SpvOpVariable, 3, 7, SpvStorageClassInput);
        if (existing) op(words, SpvOpVariable, 13, 14, SpvStorageClassInput);
        op(words, SpvOpFunction, 1, 8, 0, 4);
        op(words, SpvOpLabel, 9);
        op(words, SpvOpLoad, 2, 6, 5);
        op(words, SpvOpLoad, 2, 10, 7);
        op(words, SpvOpReturn);
        op(words, SpvOpFunctionEnd);
        var result = ByteBuffer.allocateDirect((words.size() + 4) * 4).order(ByteOrder.LITTLE_ENDIAN);
        result.position(8);
        for (int word : words) result.putInt(word);
        result.flip().position(8);
        return result;
    }

    private static void op(ArrayList<Integer> words, int op, int... args) {
        words.add(((args.length + 1) << 16) | op);
        for (int arg : args) words.add(arg);
    }

    private static ArrayList<int[]> instructions(ByteBuffer source) {
        var words = source.duplicate().order(ByteOrder.LITTLE_ENDIAN).asIntBuffer();
        var result = new ArrayList<int[]>();
        words.position(5);
        while (words.hasRemaining()) {
            int header = words.get(), length = header >>> 16;
            int[] instruction = new int[length];
            instruction[0] = header & 65535;
            for (int i = 1; i < length; i++) instruction[i] = words.get();
            result.add(instruction);
        }
        return result;
    }
}
