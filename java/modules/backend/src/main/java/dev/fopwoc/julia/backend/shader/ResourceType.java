package dev.fopwoc.julia.backend.shader;

import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL20.*;
import static org.lwjgl.opengl.GL21.*;
import static org.lwjgl.opengl.GL30.*;
import static org.lwjgl.util.spvc.Spvc.*;

final class ResourceType {
    private ResourceType() {}

    static int glType(int base, int vector, int columns) {
        if (vector < 1 || vector > 4 || columns < 1 || columns > 4)
            throw new UnsupportedOperationException("Unsupported shader numeric shape");
        if (columns > 1) {
            if (base != SPVC_BASETYPE_FP32 || vector < 2)
                throw new UnsupportedOperationException("Unsupported shader matrix type");
            return switch (columns * 10 + vector) {
                case 22 -> GL_FLOAT_MAT2;
                case 23 -> GL_FLOAT_MAT2x3;
                case 24 -> GL_FLOAT_MAT2x4;
                case 32 -> GL_FLOAT_MAT3x2;
                case 33 -> GL_FLOAT_MAT3;
                case 34 -> GL_FLOAT_MAT3x4;
                case 42 -> GL_FLOAT_MAT4x2;
                case 43 -> GL_FLOAT_MAT4x3;
                case 44 -> GL_FLOAT_MAT4;
                default -> throw new UnsupportedOperationException("Unsupported shader matrix shape");
            };
        }
        return switch (base) {
            case SPVC_BASETYPE_FP32 -> new int[] {GL_FLOAT, GL_FLOAT_VEC2, GL_FLOAT_VEC3, GL_FLOAT_VEC4}[vector - 1];
            case SPVC_BASETYPE_INT32 -> new int[] {GL_INT, GL_INT_VEC2, GL_INT_VEC3, GL_INT_VEC4}[vector - 1];
            case SPVC_BASETYPE_UINT32 ->
                new int[] {GL_UNSIGNED_INT, GL_UNSIGNED_INT_VEC2, GL_UNSIGNED_INT_VEC3, GL_UNSIGNED_INT_VEC4}
                        [vector - 1];
            case SPVC_BASETYPE_BOOLEAN -> new int[] {GL_BOOL, GL_BOOL_VEC2, GL_BOOL_VEC3, GL_BOOL_VEC4}[vector - 1];
            default -> throw new UnsupportedOperationException("Unsupported shader numeric base: " + base);
        };
    }
}
