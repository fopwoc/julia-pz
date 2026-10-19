package dev.fopwoc.julia.backend.vertex;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class VertexInputsTest {
    @Test
    void replayRejectsUnencodableUnsignedDivisorsInsteadOfClamping() {
        assertEquals(0, VertexInputs.replayDivisor(0, 65535));
        assertEquals(65535, VertexInputs.replayDivisor(65535, 65535));
        for (int divisor : new int[] {65536, 65538, Integer.MIN_VALUE, -1}) {
            var failure =
                    assertThrows(UnsupportedOperationException.class, () -> VertexInputs.replayDivisor(divisor, 65535));
            assertTrue(failure.getMessage().contains(Integer.toUnsignedString(divisor)));
        }
    }
}
