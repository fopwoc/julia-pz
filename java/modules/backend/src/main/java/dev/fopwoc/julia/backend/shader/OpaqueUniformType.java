package dev.fopwoc.julia.backend.shader;

import static org.lwjgl.opengl.GL20.*;
import static org.lwjgl.opengl.GL30.*;
import static org.lwjgl.opengl.GL31.*;
import static org.lwjgl.opengl.GL32.*;
import static org.lwjgl.opengl.GL40.*;
import static org.lwjgl.opengl.GL42.*;
import static org.lwjgl.util.spvc.Spv.*;
import static org.lwjgl.util.spvc.Spvc.*;

/** SPIR-V opaque dimensions are independent of the sampler's currently assigned texture unit. */
final class OpaqueUniformType {
    private OpaqueUniformType() {}

    static int read(long compiler, long type, boolean sampler) {
        int dimension = spvc_type_get_image_dimension(type);
        boolean array = spvc_type_get_image_arrayed(type), ms = spvc_type_get_image_multisampled(type);
        int scalar =
                spvc_type_get_basetype(spvc_compiler_get_type_handle(compiler, spvc_type_get_image_sampled_type(type)));
        int kind = switch (scalar) {
            case SPVC_BASETYPE_FP32 -> 0;
            case SPVC_BASETYPE_INT32 -> 1;
            case SPVC_BASETYPE_UINT32 -> 2;
            default -> throw new UnsupportedOperationException("Unsupported opaque uniform scalar: " + scalar);
        };
        if (sampler && spvc_type_get_image_is_depth(type)) {
            if (kind != 0 || ms) throw new UnsupportedOperationException("Invalid shadow sampler shape");
            return switch (dimension) {
                case SpvDim1D -> array ? GL_SAMPLER_1D_ARRAY_SHADOW : GL_SAMPLER_1D_SHADOW;
                case SpvDim2D -> array ? GL_SAMPLER_2D_ARRAY_SHADOW : GL_SAMPLER_2D_SHADOW;
                case SpvDimCube -> array ? GL_SAMPLER_CUBE_MAP_ARRAY_SHADOW : GL_SAMPLER_CUBE_SHADOW;
                case SpvDimRect -> GL_SAMPLER_2D_RECT_SHADOW;
                default -> throw new UnsupportedOperationException("Unsupported shadow sampler dimension");
            };
        }
        int[] types;
        if (sampler)
            types = switch (dimension) {
                case SpvDim1D ->
                    array
                            ? new int[] {GL_SAMPLER_1D_ARRAY, GL_INT_SAMPLER_1D_ARRAY, GL_UNSIGNED_INT_SAMPLER_1D_ARRAY}
                            : new int[] {GL_SAMPLER_1D, GL_INT_SAMPLER_1D, GL_UNSIGNED_INT_SAMPLER_1D};
                case SpvDim2D ->
                    ms
                            ? (array
                                    ? new int[] {
                                        GL_SAMPLER_2D_MULTISAMPLE_ARRAY,
                                        GL_INT_SAMPLER_2D_MULTISAMPLE_ARRAY,
                                        GL_UNSIGNED_INT_SAMPLER_2D_MULTISAMPLE_ARRAY
                                    }
                                    : new int[] {
                                        GL_SAMPLER_2D_MULTISAMPLE,
                                        GL_INT_SAMPLER_2D_MULTISAMPLE,
                                        GL_UNSIGNED_INT_SAMPLER_2D_MULTISAMPLE
                                    })
                            : (array
                                    ? new int[] {
                                        GL_SAMPLER_2D_ARRAY, GL_INT_SAMPLER_2D_ARRAY, GL_UNSIGNED_INT_SAMPLER_2D_ARRAY
                                    }
                                    : new int[] {GL_SAMPLER_2D, GL_INT_SAMPLER_2D, GL_UNSIGNED_INT_SAMPLER_2D});
                case SpvDim3D -> new int[] {GL_SAMPLER_3D, GL_INT_SAMPLER_3D, GL_UNSIGNED_INT_SAMPLER_3D};
                case SpvDimCube ->
                    array
                            ? new int[] {
                                GL_SAMPLER_CUBE_MAP_ARRAY,
                                GL_INT_SAMPLER_CUBE_MAP_ARRAY,
                                GL_UNSIGNED_INT_SAMPLER_CUBE_MAP_ARRAY
                            }
                            : new int[] {GL_SAMPLER_CUBE, GL_INT_SAMPLER_CUBE, GL_UNSIGNED_INT_SAMPLER_CUBE};
                case SpvDimRect ->
                    new int[] {GL_SAMPLER_2D_RECT, GL_INT_SAMPLER_2D_RECT, GL_UNSIGNED_INT_SAMPLER_2D_RECT};
                case SpvDimBuffer ->
                    new int[] {GL_SAMPLER_BUFFER, GL_INT_SAMPLER_BUFFER, GL_UNSIGNED_INT_SAMPLER_BUFFER};
                default -> throw new UnsupportedOperationException("Unsupported sampler dimension: " + dimension);
            };
        else
            types = switch (dimension) {
                case SpvDim1D ->
                    array
                            ? new int[] {GL_IMAGE_1D_ARRAY, GL_INT_IMAGE_1D_ARRAY, GL_UNSIGNED_INT_IMAGE_1D_ARRAY}
                            : new int[] {GL_IMAGE_1D, GL_INT_IMAGE_1D, GL_UNSIGNED_INT_IMAGE_1D};
                case SpvDim2D ->
                    ms
                            ? (array
                                    ? new int[] {
                                        GL_IMAGE_2D_MULTISAMPLE_ARRAY,
                                        GL_INT_IMAGE_2D_MULTISAMPLE_ARRAY,
                                        GL_UNSIGNED_INT_IMAGE_2D_MULTISAMPLE_ARRAY
                                    }
                                    : new int[] {
                                        GL_IMAGE_2D_MULTISAMPLE,
                                        GL_INT_IMAGE_2D_MULTISAMPLE,
                                        GL_UNSIGNED_INT_IMAGE_2D_MULTISAMPLE
                                    })
                            : (array
                                    ? new int[] {
                                        GL_IMAGE_2D_ARRAY, GL_INT_IMAGE_2D_ARRAY, GL_UNSIGNED_INT_IMAGE_2D_ARRAY
                                    }
                                    : new int[] {GL_IMAGE_2D, GL_INT_IMAGE_2D, GL_UNSIGNED_INT_IMAGE_2D});
                case SpvDim3D -> new int[] {GL_IMAGE_3D, GL_INT_IMAGE_3D, GL_UNSIGNED_INT_IMAGE_3D};
                case SpvDimCube ->
                    array
                            ? new int[] {
                                GL_IMAGE_CUBE_MAP_ARRAY,
                                GL_INT_IMAGE_CUBE_MAP_ARRAY,
                                GL_UNSIGNED_INT_IMAGE_CUBE_MAP_ARRAY
                            }
                            : new int[] {GL_IMAGE_CUBE, GL_INT_IMAGE_CUBE, GL_UNSIGNED_INT_IMAGE_CUBE};
                case SpvDimRect -> new int[] {GL_IMAGE_2D_RECT, GL_INT_IMAGE_2D_RECT, GL_UNSIGNED_INT_IMAGE_2D_RECT};
                case SpvDimBuffer -> new int[] {GL_IMAGE_BUFFER, GL_INT_IMAGE_BUFFER, GL_UNSIGNED_INT_IMAGE_BUFFER};
                default -> throw new UnsupportedOperationException("Unsupported image dimension: " + dimension);
            };
        return types[kind];
    }
}
