package dev.fopwoc.julia.backend.texture;

import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL12.*;
import static org.lwjgl.opengl.GL13.*;
import static org.lwjgl.opengl.GL30.*;
import static org.lwjgl.opengl.GL31.*;
import static org.lwjgl.opengl.GL32.*;
import static org.lwjgl.opengl.GL40.*;
import static org.lwjgl.util.spvc.Spv.*;
import static org.lwjgl.util.spvc.Spvc.*;

import com.gtnewhorizons.angelica.sdlgpu.sampler.SamplerLookup;
import com.gtnewhorizons.angelica.sdlgpu.shader.ShaderManager.ProgramObject;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.BitSet;
import java.util.HashMap;
import java.util.Map;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.util.spvc.SpvcReflectedResource;

/** Reflected sampler targets and buffer types, cached by compiled stages and sampler-unit assignments. */
public final class SamplerLayout {
    private final ByteBuffer vertex, fragment, compute;
    private final Map<String, Integer> names = new HashMap<>();
    private final Map<String, Integer> targets = new HashMap<>();
    private final int[] unitTargets = new int[TextureBindings.UNIT_COUNT];
    private int generation = Integer.MIN_VALUE;
    private BitSet bufferUnits = new BitSet(TextureBindings.UNIT_COUNT);

    public SamplerLayout(ProgramObject program) {
        vertex = program.vertexSpirv;
        fragment = program.fragmentSpirv;
        compute = program.computeSpirv;
        read(vertex);
        read(fragment);
        read(compute);
    }

    public boolean matches(ProgramObject program) {
        return vertex == program.vertexSpirv && fragment == program.fragmentSpirv && compute == program.computeSpirv;
    }

    public int uniformType(String name, int fallback) {
        return names.getOrDefault(name, fallback);
    }

    private void refreshUnits(ProgramObject program) {
        SamplerLookup.resolvedUnits(program, false);
        if (generation == program.samplerUnitsGeneration) return;
        var buffers = new BitSet(TextureBindings.UNIT_COUNT);
        var ordinary = new BitSet(TextureBindings.UNIT_COUNT);
        Arrays.fill(unitTargets, 0);
        for (var name : program.allSamplerNames) {
            // Unreachable helpers can retain declarations without an active uniform location.
            if (!program.nameToLocation.containsKey(name)) continue;
            int assigned = program.samplerTextureUnits.getInt(name);
            int unit = assigned == -1 ? 0 : assigned;
            if (unit < 0 || unit >= unitTargets.length)
                throw new IllegalArgumentException("Sampler texture unit out of range: " + unit);
            int target = targets.getOrDefault(name, 0);
            if (target == 0 && name.endsWith("]"))
                target = targets.getOrDefault(name.substring(0, name.lastIndexOf('[')), 0);
            if (target == 0) throw new UnsupportedOperationException("Sampler target reflection missing: " + name);
            if (unitTargets[unit] != 0 && unitTargets[unit] != target)
                throw new IllegalStateException("Different sampler targets share texture unit " + unit + ": " + name
                        + " uses " + target + ", already " + unitTargets[unit]);
            unitTargets[unit] = target;
            if (names.containsKey(name)) buffers.set(unit);
            else ordinary.set(unit);
        }
        if (buffers.intersects(ordinary))
            throw new IllegalStateException("Different sampler types share a texture unit");
        bufferUnits = buffers;
        generation = program.samplerUnitsGeneration;
    }

    public boolean bufferUnit(ProgramObject program, int unit) {
        refreshUnits(program);
        return bufferUnits.get(unit);
    }

    public int nextBufferUnit(ProgramObject program, int from) {
        refreshUnits(program);
        return bufferUnits.nextSetBit(from);
    }

    public int ordinaryTarget(ProgramObject program, int unit) {
        refreshUnits(program);
        return unitTargets[unit] == 0 ? GL_TEXTURE_2D : unitTargets[unit];
    }

    private void read(ByteBuffer spirv) {
        if (spirv == null) return;
        try (var stack = MemoryStack.stackPush()) {
            var pointer = stack.pointers(0);
            check(spvc_context_create(pointer));
            long context = pointer.get(0);
            try {
                var words = spirv.duplicate().order(ByteOrder.LITTLE_ENDIAN).asIntBuffer();
                check(spvc_context_parse_spirv(context, words, words.remaining(), pointer));
                check(spvc_context_create_compiler(
                        context, SPVC_BACKEND_NONE, pointer.get(0), SPVC_CAPTURE_MODE_TAKE_OWNERSHIP, pointer));
                long compiler = pointer.get(0);
                check(spvc_compiler_create_shader_resources(compiler, pointer));
                long resources = pointer.get(0);
                var count = stack.pointers(0);
                check(spvc_resources_get_resource_list_for_type(
                        resources, SPVC_RESOURCE_TYPE_SAMPLED_IMAGE, pointer, count));
                var list = SpvcReflectedResource.create(pointer.get(0), Math.toIntExact(count.get(0)));
                for (var resource : list) {
                    long type = spvc_compiler_get_type_handle(compiler, resource.type_id());
                    int dimension = spvc_type_get_image_dimension(type);
                    boolean array = spvc_type_get_image_arrayed(type);
                    int target = switch (dimension) {
                        case SpvDimBuffer -> GL_TEXTURE_BUFFER;
                        case SpvDim1D -> array ? GL_TEXTURE_1D_ARRAY : GL_TEXTURE_1D;
                        case SpvDim2D ->
                            spvc_type_get_image_multisampled(type)
                                    ? (array ? GL_TEXTURE_2D_MULTISAMPLE_ARRAY : GL_TEXTURE_2D_MULTISAMPLE)
                                    : (array ? GL_TEXTURE_2D_ARRAY : GL_TEXTURE_2D);
                        case SpvDim3D -> GL_TEXTURE_3D;
                        case SpvDimCube -> array ? GL_TEXTURE_CUBE_MAP_ARRAY : GL_TEXTURE_CUBE_MAP;
                        case SpvDimRect -> GL_TEXTURE_RECTANGLE;
                        default ->
                            throw new UnsupportedOperationException("Sampler dimension not implemented: " + dimension);
                    };
                    targets.put(resource.nameString(), target);
                    if (dimension != SpvDimBuffer) continue;
                    if (spvc_type_get_num_array_dimensions(type) != 0)
                        throw new UnsupportedOperationException(
                                "Arrays of buffer-texture samplers are not implemented");
                    int scalar = spvc_type_get_basetype(
                            spvc_compiler_get_type_handle(compiler, spvc_type_get_image_sampled_type(type)));
                    int glType = switch (scalar) {
                        case SPVC_BASETYPE_FP32 -> GL_SAMPLER_BUFFER;
                        case SPVC_BASETYPE_INT32 -> GL_INT_SAMPLER_BUFFER;
                        case SPVC_BASETYPE_UINT32 -> GL_UNSIGNED_INT_SAMPLER_BUFFER;
                        default ->
                            throw new UnsupportedOperationException(
                                    "Unsupported buffer sampler scalar type: " + scalar);
                    };
                    names.put(resource.nameString(), glType);
                }
            } finally {
                spvc_context_destroy(context);
            }
        }
    }

    private static void check(int result) {
        if (result != SPVC_SUCCESS) throw new IllegalStateException("Sampler reflection failed: " + result);
    }
}
