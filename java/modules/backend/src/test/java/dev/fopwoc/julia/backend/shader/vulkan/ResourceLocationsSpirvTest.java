package dev.fopwoc.julia.backend.shader.vulkan;

import static org.junit.jupiter.api.Assertions.*;
import static org.lwjgl.util.spvc.Spv.*;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import org.junit.jupiter.api.Test;

class ResourceLocationsSpirvTest {
    @Test
    void removesOnlyResourceLocationsAndPreservesCallerMemory() {
        int[] header = {SpvMagicNumber, 0x10000, 0, 20, 0};
        int[] retained = {
            (4 << 16) | SpvOpDecorate, 7, SpvDecorationBinding, 3,
            (4 << 16) | SpvOpDecorate, 7, SpvDecorationDescriptorSet, 2,
            (4 << 16) | SpvOpDecorate, 8, SpvDecorationLocation, 1,
            (4 << 16) | SpvOpDecorate, 9, SpvDecorationLocation, 0,
            (4 << 16) | SpvOpVariable, 2, 7, SpvStorageClassUniformConstant,
            (4 << 16) | SpvOpVariable, 3, 8, SpvStorageClassInput,
            (4 << 16) | SpvOpVariable, 3, 9, SpvStorageClassOutput
        };
        var source = ByteBuffer.allocateDirect(512).order(ByteOrder.LITTLE_ENDIAN);
        source.putInt(123);
        for (int word : header) source.putInt(word);
        source.putInt((4 << 16) | SpvOpDecorate)
                .putInt(7)
                .putInt(SpvDecorationLocation)
                .putInt(12);
        for (int word : retained) source.putInt(word);
        source.flip().position(4);
        byte[] original = new byte[source.remaining()];
        source.duplicate().get(original);
        var translated = ResourceLocationsSpirv.translate(source);
        byte[] after = new byte[source.remaining()];
        source.duplicate().get(after);
        assertArrayEquals(original, after);
        assertEquals(4, source.position());
        var expected =
                ByteBuffer.allocateDirect((header.length + retained.length) * 4).order(ByteOrder.LITTLE_ENDIAN);
        for (int word : header) expected.putInt(word);
        for (int word : retained) expected.putInt(word);
        assertEquals(expected.flip(), translated);
        assertSame(translated, ResourceLocationsSpirv.translate(translated));
    }

    @Test
    void rejectsTruncatedAndZeroLengthInstructions() {
        for (int instruction : new int[] {0, (4 << 16) | SpvOpDecorate}) {
            var source = ByteBuffer.allocateDirect(24).order(ByteOrder.LITTLE_ENDIAN);
            source.putInt(SpvMagicNumber)
                    .putInt(0x10000)
                    .putInt(0)
                    .putInt(20)
                    .putInt(0)
                    .putInt(instruction)
                    .flip();
            assertThrows(IllegalArgumentException.class, () -> ResourceLocationsSpirv.translate(source));
        }
    }
}
