package dev.fopwoc.julia.backend.vertex.macos;

import static org.lwjgl.sdl.SDLGPU.*;
import static org.lwjgl.sdl.SDLProperties.*;

import dev.fopwoc.julia.backend.utils.Sdl;
import org.lwjgl.sdl.SDL;
import org.lwjgl.sdl.SDL_GPUGraphicsPipelineCreateInfo;
import org.lwjgl.system.JNI;

/** SDL's reserved step-rate field stays zero; Julia's Metal extension consumes scoped pipeline properties. */
public final class MetalVertexInputs {
    private static final String PREFIX = "julia.gpu.vertex_divisor.";

    static {
        long abi = SDL.getLibrary().getFunctionAddress("JULIA_SDL_VertexInputABI");
        if (abi == 0 || JNI.invokeI(abi) != 2)
            throw new IllegalStateException("Missing native Metal vertex-input extension");
    }

    private MetalVertexInputs() {}

    public static long create(long device, SDL_GPUGraphicsPipelineCreateInfo info, int[] rates) {
        int previous = info.props();
        int properties = SDL_CreateProperties();
        if (properties == 0) throw Sdl.failure("Create vertex-input pipeline properties");
        try {
            if (previous != 0 && !SDL_CopyProperties(previous, properties))
                throw Sdl.failure("Copy graphics pipeline properties");
            var descriptions = info.vertex_input_state().vertex_buffer_descriptions();
            if (descriptions != null)
                for (var description : descriptions) {
                    int slot = description.slot();
                    if (slot < 0 || slot >= rates.length)
                        throw new IllegalArgumentException("Unexpected SDL vertex buffer slot");
                    // SDL tags defaults as instance-rate inputs; Metal must ignore the native base instance.
                    if (rates[slot] == 0 && description.input_rate() == SDL_GPU_VERTEXINPUTRATE_INSTANCE) {
                        if (!SDL_SetNumberProperty(properties, PREFIX + slot, -1))
                            throw Sdl.failure("Set constant Metal vertex input");
                        continue;
                    }
                    int divisor = rates[slot];
                    description.input_rate(
                            divisor == 0 ? SDL_GPU_VERTEXINPUTRATE_VERTEX : SDL_GPU_VERTEXINPUTRATE_INSTANCE);
                    if (divisor != 0
                            && !SDL_SetNumberProperty(properties, PREFIX + slot, Integer.toUnsignedLong(divisor)))
                        throw Sdl.failure("Set Metal vertex divisor");
                }
            info.props(properties);
            return Sdl.require(SDL_CreateGPUGraphicsPipeline(device, info), "Create vertex-input graphics pipeline");
        } finally {
            info.props(previous);
            SDL_DestroyProperties(properties);
        }
    }
}
