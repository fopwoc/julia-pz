package dev.fopwoc.julia.backend.platform;

import com.gtnewhorizons.angelica.sdlgpu.shader.cross.CrossCompileCache.Output;
import dev.fopwoc.julia.backend.query.TimestampDriver;
import dev.fopwoc.julia.backend.query.macos.MetalTimestamps;
import dev.fopwoc.julia.backend.shader.macos.MetalSamplerShaders;
import dev.fopwoc.julia.backend.texture.buffer.BufferTextureDriver;
import dev.fopwoc.julia.backend.texture.macos.MetalBufferTextures;
import dev.fopwoc.julia.backend.texture.macos.MetalTextureViews;
import dev.fopwoc.julia.backend.vertex.macos.MetalVertexInputs;
import java.nio.ByteBuffer;
import java.util.Locale;
import org.lwjgl.sdl.SDL_GPUGraphicsPipelineCreateInfo;
import org.lwjgl.system.Configuration;
import org.lwjgl.system.JNI;
import org.lwjgl.system.macosx.LibSystem;

/** Host operating system facts the graphics runtime depends on: GPU driver and video-thread rules. */
public enum Platform {
    MACOS("metal") {
        @Override
        public Output compileSamplerShader(ByteBuffer spirv, int stage) {
            return MetalSamplerShaders.compile(spirv, stage);
        }

        @Override
        public long createGraphicsPipeline(long device, SDL_GPUGraphicsPipelineCreateInfo info, int[] divisors) {
            return MetalVertexInputs.create(device, info, divisors);
        }

        @Override
        public long createTextureView(long source, int type, int level, int levels, int layer, int layers) {
            return MetalTextureViews.create(source, type, level, levels, layer, layers);
        }

        @Override
        public BufferTextureDriver createBufferTextureDriver() {
            return new MetalBufferTextures();
        }

        @Override
        public TimestampDriver createTimestampDriver() {
            return new MetalTimestamps();
        }

        @Override
        public void configureNativeLibraries() {
            Configuration.SDL_LIBRARY_NAME.set("SDL3Julia");
        }

        @Override
        public void requireVideoThread() {
            // Cocoa only permits SDL video initialization on the process main thread.
            long isMainThread = LibSystem.getLibrary().getFunctionAddress("pthread_main_np");
            if (isMainThread == 0 || JNI.invokeI(isMainThread) == 0) {
                throw new IllegalStateException(
                        "SDL video initialization requires the Cocoa main thread; current thread: "
                                + Thread.currentThread().getName());
            }
        }
    },
    // Driver choices for the planned ports; their runtime paths are not implemented or verified yet.
    WINDOWS("direct3d12"),
    LINUX("vulkan");

    private final String gpuDriver;

    Platform(String gpuDriver) {
        this.gpuDriver = gpuDriver;
    }

    public static Platform current() {
        String name = System.getProperty("os.name").toLowerCase(Locale.ROOT);
        if (name.startsWith("mac")) return MACOS;
        if (name.startsWith("windows")) return WINDOWS;
        if (name.startsWith("linux")) return LINUX;
        throw new UnsupportedOperationException("Unsupported operating system: " + name);
    }

    /** SDL GPU driver name requested from Angelica's backend options and expected from the created device. */
    public String gpuDriver() {
        return gpuDriver;
    }

    /** Fails fast where the platform restricts which thread may initialize SDL video. */
    public void requireVideoThread() {}

    public void configureNativeLibraries() {}

    public long createGraphicsPipeline(long device, SDL_GPUGraphicsPipelineCreateInfo info, int[] divisors) {
        throw new UnsupportedOperationException("Vertex input pipeline creation is not implemented on " + this);
    }

    public long createTextureView(long source, int type, int level, int levels, int layer, int layers) {
        throw new UnsupportedOperationException("Native texture views are not implemented on " + this);
    }

    public BufferTextureDriver createBufferTextureDriver() {
        throw new UnsupportedOperationException("Native buffer textures are not implemented on " + this);
    }

    public TimestampDriver createTimestampDriver() {
        throw new UnsupportedOperationException("Native GPU timestamps are not implemented on " + this);
    }

    public Output compileSamplerShader(ByteBuffer spirv, int stage) {
        throw new UnsupportedOperationException("Indirect samplers not implemented on " + this);
    }

    public void requireImplemented() {
        if (this != MACOS) throw new UnsupportedOperationException("The graphics runtime is not yet ported to " + this);
    }
}
