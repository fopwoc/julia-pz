package dev.fopwoc.julia.backend.shader;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class ResourceOutputTest {
    @Test
    void nameCapacityIncludesTerminator() {
        assertEquals("", ResourceOutput.name("matrix", 0));
        assertEquals("", ResourceOutput.name("matrix", 1));
        assertEquals("mat", ResourceOutput.name("matrix", 4));
        assertEquals("matrix", ResourceOutput.name("matrix", 7));
        assertThrows(IllegalArgumentException.class, () -> ResourceOutput.name("matrix", -1));
    }

    @Test
    void truncatesPropertiesAndReportsActualWrittenCount() {
        int[] length = {-1}, output = {-1, -1};
        ResourceOutput.write(new int[] {3, 7, 9}, length, output);
        assertArrayEquals(new int[] {3, 7}, output);
        assertEquals(2, length[0]);
        ResourceOutput.write(new int[] {9}, null, output);
        assertArrayEquals(new int[] {9, 7}, output);
        ResourceOutput.write(new int[] {9}, length, new int[0]);
        assertEquals(0, length[0]);
    }

    @Test
    void rejectsInvalidLengthBeforeChangingOutput() {
        int[] output = {-1};
        assertThrows(IllegalArgumentException.class, () -> ResourceOutput.write(new int[] {9}, new int[0], output));
        assertEquals(-1, output[0]);
    }
}
