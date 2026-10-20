package dev.fopwoc.julia.backend.shader;

import static org.lwjgl.util.spvc.Spv.*;
import static org.lwjgl.util.spvc.Spvc.*;

import com.gtnewhorizons.angelica.sdlgpu.shader.ShaderManager.ProgramObject;
import com.gtnewhorizons.angelica.sdlgpu.shader.ShaderManager.ResourceCounts;
import com.gtnewhorizons.angelica.sdlgpu.shader.ShaderManager.StageReflection;
import it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import java.util.List;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.util.spvc.SpvcReflectedResource;

/** Resource arrays occupy their full binding width in SDL and Metal, with stable per-element GL names. */
public final class SamplerResources {
    public static final int LIMIT = 32;

    private SamplerResources() {}

    /** Only entry-point-reachable resources consume bindings; runtime-conditional reads remain active. */
    public static long activeResources(long compiler, MemoryStack stack) {
        var active = stack.pointers(0);
        check(spvc_compiler_get_active_interface_variables(compiler, active));
        var resources = stack.pointers(0);
        check(spvc_compiler_create_shader_resources_for_active_variables(compiler, resources, active.get(0)));
        return resources.get(0);
    }

    public static int width(long compiler, SpvcReflectedResource resource) {
        long type = spvc_compiler_get_type_handle(compiler, resource.type_id());
        int dimensions = spvc_type_get_num_array_dimensions(type);
        if (dimensions == 0) return 1;
        if (dimensions != 1 || !spvc_type_array_dimension_is_literal(type, 0))
            throw new UnsupportedOperationException(
                    "Resource arrays require one literal dimension: " + resource.nameString());
        int size = spvc_type_get_array_dimension(type, 0);
        if (size <= 0 || size > LIMIT)
            throw new UnsupportedOperationException("Resource array width outside 1.." + LIMIT + ": " + size);
        return size;
    }

    public static int remap(
            long resources,
            long compiler,
            int kind,
            Int2IntOpenHashMap sets,
            Int2IntOpenHashMap bindings,
            int set,
            int next,
            MemoryStack stack) {
        var pointer = stack.pointers(0);
        var count = stack.pointers(0);
        long selected = kind == SPVC_RESOURCE_TYPE_SAMPLED_IMAGE ? activeResources(compiler, stack) : resources;
        check(spvc_resources_get_resource_list_for_type(selected, kind, pointer, count));
        var list = SpvcReflectedResource.create(pointer.get(0), Math.toIntExact(count.get(0)));
        for (var resource : list) {
            int width = width(compiler, resource);
            if (width != 1 && kind != SPVC_RESOURCE_TYPE_SAMPLED_IMAGE)
                throw new UnsupportedOperationException("Only sampled-image resource arrays are implemented");
            sets.put(resource.id(), set);
            bindings.put(resource.id(), next);
            next = Math.addExact(next, width);
        }
        return next;
    }

    public static void entries(
            long resources, long compiler, int kind, List<String> names, IntArrayList bindings, MemoryStack stack) {
        var pointer = stack.pointers(0);
        var count = stack.pointers(0);
        long selected = kind == SPVC_RESOURCE_TYPE_SAMPLED_IMAGE ? activeResources(compiler, stack) : resources;
        check(spvc_resources_get_resource_list_for_type(selected, kind, pointer, count));
        var list = SpvcReflectedResource.create(pointer.get(0), Math.toIntExact(count.get(0)));
        for (var resource : list) {
            int width = width(compiler, resource);
            long type = spvc_compiler_get_type_handle(compiler, resource.type_id());
            boolean array = spvc_type_get_num_array_dimensions(type) != 0;
            int binding = spvc_compiler_get_decoration(compiler, resource.id(), SpvDecorationBinding);
            for (int i = 0; i < width; i++) {
                names.add(resource.nameString() + (array ? "[" + i + "]" : ""));
                bindings.add(binding + i);
            }
        }
    }

    public static void register(ProgramObject program, StageReflection... stages) {
        for (var stage : stages)
            for (String name : stage.samplerNames()) {
                int bracket = name.lastIndexOf('[');
                String base = bracket < 0 ? name : name.substring(0, bracket);
                if (stage.unusedSamplerNames().contains(base)
                        || stage.unusedSamplerNames().contains(name)
                        || program.nameToLocation.containsKey(name)) continue;
                int location = program.nextUniformLocation++;
                program.nameToLocation.put(name, location);
                program.locationToName.put(location, name);
            }
    }

    public static String budget(String stage, ResourceCounts counts, boolean[] moved) {
        if (counts.numSamplers() > LIMIT)
            return "Too many " + stage + " shader texture samplers (" + counts.numSamplers() + ", maximum " + LIMIT
                    + ")";
        if (counts.numStorageTextures() > 8)
            return stage + " shader storage texture limit exceeded: " + counts.numStorageTextures();
        return null;
    }

    public static void check(int result) {
        if (result != SPVC_SUCCESS) throw new IllegalStateException("Sampler resource translation failed: " + result);
    }
}
