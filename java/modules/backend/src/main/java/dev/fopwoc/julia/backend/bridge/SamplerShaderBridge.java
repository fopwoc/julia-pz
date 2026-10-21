package dev.fopwoc.julia.backend.bridge;

import static org.lwjgl.util.spvc.Spvc.*;

import com.gtnewhorizons.angelica.sdlgpu.shader.cross.CrossCompileCache.Output;
import dev.fopwoc.julia.backend.gpu.GpuBackend;
import dev.fopwoc.julia.backend.shader.SamplerResources;
import dev.fopwoc.julia.backend.utils.Sdl;
import dev.fopwoc.julia.runtime.FatalGraphics;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import org.lwjgl.opengl.GL20;
import org.lwjgl.sdl.SDLGPU;
import org.lwjgl.sdl.SDL_GPUComputePipelineCreateInfo;
import org.lwjgl.sdl.SDL_GPUShaderCreateInfo;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.util.spvc.SpvcReflectedResource;

/** Selects indirect graphics bindings only when a stage exceeds direct slots or contains a resource array. */
public final class SamplerShaderBridge {
    private SamplerShaderBridge() {}

    public static boolean needsArguments(ByteBuffer spirv, int stage) {
        if (stage != GL20.GL_VERTEX_SHADER && stage != GL20.GL_FRAGMENT_SHADER) return false;
        try (var stack = MemoryStack.stackPush()) {
            var pointer = stack.pointers(0);
            SamplerResources.check(spvc_context_create(pointer));
            long context = pointer.get(0);
            try {
                var words = spirv.duplicate().order(ByteOrder.LITTLE_ENDIAN).asIntBuffer();
                SamplerResources.check(spvc_context_parse_spirv(context, words, words.remaining(), pointer));
                SamplerResources.check(spvc_context_create_compiler(
                        context, SPVC_BACKEND_NONE, pointer.get(0), SPVC_CAPTURE_MODE_TAKE_OWNERSHIP, pointer));
                long compiler = pointer.get(0);
                long resources = SamplerResources.activeResources(compiler, stack);
                var count = stack.pointers(0);
                SamplerResources.check(spvc_resources_get_resource_list_for_type(
                        resources, SPVC_RESOURCE_TYPE_SAMPLED_IMAGE, pointer, count));
                var list = SpvcReflectedResource.create(pointer.get(0), Math.toIntExact(count.get(0)));
                int total = 0;
                for (var resource : list) {
                    long type = spvc_compiler_get_type_handle(compiler, resource.type_id());
                    if (spvc_type_get_num_array_dimensions(type) != 0) return true;
                    total += SamplerResources.width(compiler, resource);
                }
                return total > 16;
            } finally {
                spvc_context_destroy(context);
            }
        }
    }

    public static void requireComputeLayout(ByteBuffer spirv) {
        if (needsArguments(spirv, GL20.GL_FRAGMENT_SHADER))
            throw new UnsupportedOperationException(
                    "Compute sampled resources require scalar bindings within the direct 16-slot limit");
    }

    // Angelica treats a missing shader as "skip the draw", which would silently drop geometry. A shader GL already
    // linked must exist natively, so a failure here is fatal even when it happens on an async compile thread.
    public static long createShader(long device, SDL_GPUShaderCreateInfo info) {
        return requireCreated(
                GpuBackend.of(SDLGPU.SDL_GetGPUDeviceDriver(device)).createShader(device, info), "Create GPU shader");
    }

    public static long createComputePipeline(long device, SDL_GPUComputePipelineCreateInfo info) {
        return requireCreated(
                GpuBackend.of(SDLGPU.SDL_GetGPUDeviceDriver(device)).createComputePipeline(device, info),
                "Create GPU compute pipeline");
    }

    private static long requireCreated(long handle, String operation) {
        if (handle == 0) throw FatalGraphics.failure("Linked GL program has no native shader", Sdl.failure(operation));
        return handle;
    }

    public static Output compile(ByteBuffer spirv, int stage) {
        return GpuBackend.current().compileSamplerShader(spirv, stage);
    }
}
