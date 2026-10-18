package dev.julia.backend.bridge;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import org.junit.jupiter.api.Test;

class UniformValueTest {
    @Test
    void preservesIntegerBitsAndNormalizesBooleans() {
        for (int value : new int[] {0, -1, Integer.MIN_VALUE, Integer.MAX_VALUE, 16_777_217}) {
            var uniform = new UniformValue(UniformValue.Kind.INT, new float[] {Float.intBitsToFloat(value)});
            assertEquals(value, uniform.scalarInt());
            assertEquals((float) value, uniform.scalarFloat());
        }
        assertEquals(1, new UniformValue(UniformValue.Kind.BOOL, new float[] {Float.intBitsToFloat(-7)}).scalarInt());
    }

    @Test
    void preservesPositionsAndUntouchedOutputElements() {
        var value = new UniformValue(UniformValue.Kind.FLOAT, new float[] {0.25f, -0.5f});
        var output = FloatBuffer.wrap(new float[] {99, 88, 77, 66});
        output.position(1);
        value.write(output);
        assertEquals(1, output.position());
        assertArrayEquals(new float[] {99, 0.25f, -0.5f, 66}, output.array());
        assertThrows(IllegalArgumentException.class, () -> value.write(FloatBuffer.allocate(1)));
        assertThrows(IllegalArgumentException.class, () -> value.write(output.asReadOnlyBuffer()));
        assertThrows(IllegalArgumentException.class, value::scalarFloat);
    }

    @Test
    void failedConversionsNeverPartiallyWriteOutput() {
        var value = new UniformValue(UniformValue.Kind.FLOAT, new float[] {1, 0.5f});
        var output = IntBuffer.wrap(new int[] {99, 88, 77});
        assertThrows(UnsupportedOperationException.class, () -> value.write(output));
        assertArrayEquals(new int[] {99, 88, 77}, output.array());
        assertEquals(
                4294967296.0f,
                new UniformValue(UniformValue.Kind.UINT, new float[] {Float.intBitsToFloat(-1)}).scalarFloat());
    }

    @Test
    void snapshotsDoNotAliasSourceOrAccessorArrays() {
        float[] source = {1, 2};
        var value = new UniformValue(UniformValue.Kind.FLOAT, source);
        source[0] = 9;
        value.raw()[1] = 8;
        float[] output = {0, 0};
        value.write(output);
        assertArrayEquals(new float[] {1, 2}, output);
    }
}
