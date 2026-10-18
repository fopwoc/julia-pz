package dev.fopwoc.julia.backend.shader;

/** One leaf of a storage block, with offsets relative to the block's first byte. */
public record ResourceVariable(
        String name,
        int type,
        int arraySize,
        int offset,
        int arrayStride,
        int matrixStride,
        boolean rowMajor,
        int topArraySize,
        int topArrayStride) {}
