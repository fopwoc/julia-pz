package dev.fopwoc.julia.backend.gpu;

import com.gtnewhorizons.angelica.sdlgpu.SDLGPUGate;
import com.gtnewhorizons.angelica.sdlgpu.shader.cross.CrossCompileCache.Output;
import dev.fopwoc.julia.backend.shader.metal.MetalSamplerShaders;
import dev.fopwoc.julia.backend.shader.vulkan.VulkanSamplers;
import java.nio.ByteBuffer;
import org.lwjgl.sdl.SDLGPU;
import org.lwjgl.sdl.SDL_GPUComputePipelineCreateInfo;
import org.lwjgl.sdl.SDL_GPUShaderCreateInfo;

/**
 * The SDL GPU driver a device runs on. Julia's native extensions export the same C symbols for every backend that
 * implements them; only work that differs in Java, such as shader language translation, lives here.
 */
public enum GpuBackend {
    METAL("metal") {
        @Override
        public Output compileSamplerShader(ByteBuffer spirv, int stage) {
            return MetalSamplerShaders.compile(spirv, stage);
        }
    },
    // Vulkan extensions share the SDL ABI; shader descriptors retain SPIR-V resource types.
    VULKAN("vulkan") {
        @Override
        public long createShader(long device, SDL_GPUShaderCreateInfo info) {
            return VulkanSamplers.create(device, info);
        }

        @Override
        public long createComputePipeline(long device, SDL_GPUComputePipelineCreateInfo info) {
            return VulkanSamplers.createCompute(device, info);
        }
    },
    DIRECT3D12("direct3d12");

    private final String driverName;

    GpuBackend(String driverName) {
        this.driverName = driverName;
    }

    /** The backend of the created SDL device. */
    public static GpuBackend current() {
        return of(SDLGPUGate.device().getDriverName());
    }

    public static GpuBackend of(String driverName) {
        for (var backend : values()) if (backend.driverName.equalsIgnoreCase(driverName)) return backend;
        throw new UnsupportedOperationException("Unsupported SDL GPU driver: " + driverName);
    }

    /** SDL's name for this driver, as requested from Angelica and reported by the device. */
    public String driverName() {
        return driverName;
    }

    /** Shaders whose sampled images exceed SDL's per-stage table, bound through one indirect argument table. */
    public Output compileSamplerShader(ByteBuffer spirv, int stage) {
        throw new UnsupportedOperationException("Indirect sampler tables are not implemented on " + driverName);
    }

    public long createShader(long device, SDL_GPUShaderCreateInfo info) {
        return SDLGPU.SDL_CreateGPUShader(device, info);
    }

    public long createComputePipeline(long device, SDL_GPUComputePipelineCreateInfo info) {
        return SDLGPU.SDL_CreateGPUComputePipeline(device, info);
    }

    public void requireImplemented() {
        if (this == DIRECT3D12)
            throw new UnsupportedOperationException("Julia's native GPU extensions are not ported to " + driverName);
    }
}
