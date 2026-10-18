package dev.fopwoc.julia.backend.shader;

import java.util.Objects;

public final class ResourceOutput {
    private ResourceOutput() {}

    public static String name(String name, int capacity) {
        if (capacity < 0) throw new IllegalArgumentException("Negative resource name capacity");
        return name.substring(0, Math.min(name.length(), Math.max(0, capacity - 1)));
    }

    public static void write(int[] values, int[] length, int[] output) {
        Objects.requireNonNull(output);
        if (length != null && length.length == 0) throw new IllegalArgumentException("Resource length output is empty");
        int count = Math.min(values.length, output.length);
        System.arraycopy(values, 0, output, 0, count);
        if (length != null) length[0] = count;
    }
}
