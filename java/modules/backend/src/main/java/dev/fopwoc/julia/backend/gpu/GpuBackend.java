package dev.fopwoc.julia.backend.gpu;

import com.gtnewhorizons.angelica.sdlgpu.SDLGPUGate;
import com.gtnewhorizons.angelica.sdlgpu.shader.cross.CrossCompileCache.Output;
import dev.fopwoc.julia.backend.shader.metal.MetalSamplerShaders;
import java.nio.ByteBuffer;

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
    // Planned ports; their native extensions are not implemented or verified yet.
    VULKAN("vulkan"),
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

    public void requireImplemented() {
        if (this != METAL)
            throw new UnsupportedOperationException("Julia's native GPU extensions are not ported to " + driverName);
    }
}
