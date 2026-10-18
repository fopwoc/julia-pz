package dev.julia.backend.bridge;

import java.nio.FloatBuffer;
import java.nio.IntBuffer;

/** One uniform element in upstream's raw float-slot representation, with explicit numeric kind. */
record UniformValue(Kind kind, float[] raw) {
    enum Kind { FLOAT, INT, UINT, BOOL }
    UniformValue { raw = raw.clone(); }
    @Override public float[] raw() { return raw.clone(); }

    float floating(int index) {
        int bits = Float.floatToRawIntBits(raw[index]);
        return switch (kind) {
            case FLOAT -> raw[index];
            case INT -> bits;
            case UINT -> Integer.toUnsignedLong(bits);
            case BOOL -> bits == 0 ? 0 : 1;
        };
    }

    int integer(int index) {
        int bits = Float.floatToRawIntBits(raw[index]);
        return switch (kind) {
            case INT -> bits;
            case BOOL -> bits == 0 ? 0 : 1;
            case UINT -> {
                if (bits < 0) throw new UnsupportedOperationException("Unsigned value outside signed query range");
                yield bits;
            }
            case FLOAT -> {
                double value = raw[index];
                if (!Double.isFinite(value) || value < Integer.MIN_VALUE || value > Integer.MAX_VALUE || value != Math.rint(value)) {
                    throw new UnsupportedOperationException("Non-integral or out-of-range float-to-integer uniform conversion");
                }
                yield (int) value;
            }
        };
    }

    float scalarFloat() { requireScalar(); return floating(0); }
    int scalarInt() { requireScalar(); return integer(0); }
    private void requireScalar() {
        if (raw.length != 1) throw new IllegalArgumentException("Scalar uniform query used for " + raw.length + " components");
    }

    void write(FloatBuffer output) {
        if (output.isReadOnly() || output.remaining() < raw.length) throw new IllegalArgumentException("Insufficient writable float output");
        for (int i = 0; i < raw.length; i++) output.put(output.position() + i, floating(i));
    }

    void write(IntBuffer output) {
        if (output.isReadOnly() || output.remaining() < raw.length) throw new IllegalArgumentException("Insufficient writable integer output");
        int[] values = integers();
        for (int i = 0; i < values.length; i++) output.put(output.position() + i, values[i]);
    }

    void write(float[] output) {
        if (output.length < raw.length) throw new IllegalArgumentException("Insufficient float array output");
        for (int i = 0; i < raw.length; i++) output[i] = floating(i);
    }

    void write(int[] output) {
        if (output.length < raw.length) throw new IllegalArgumentException("Insufficient integer array output");
        int[] values = integers();
        System.arraycopy(values, 0, output, 0, values.length);
    }

    private int[] integers() {
        var result = new int[raw.length];
        for (int i = 0; i < result.length; i++) result[i] = integer(i);
        return result;
    }
}
