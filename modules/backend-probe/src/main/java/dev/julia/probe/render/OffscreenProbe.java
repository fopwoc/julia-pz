package dev.julia.probe.render;

import com.gtnewhorizons.angelica.sdlgpu.device.Device;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import javax.imageio.ImageIO;
import org.lwjgl.sdl.SDLInit;
import org.lwjgl.sdl.SDLError;
import org.lwjgl.sdl.SDLHints;
import org.lwjgl.sdl.SDLVersion;
import org.lwjgl.system.Configuration;
import org.lwjgl.system.JNI;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.macosx.LibSystem;
import static org.lwjgl.sdl.SDLGPU.*;
import org.lwjgl.sdl.SDL_GPUColorTargetInfo;
import org.lwjgl.sdl.SDL_GPUTextureCreateInfo;
import org.lwjgl.sdl.SDL_GPUTextureRegion;
import org.lwjgl.sdl.SDL_GPUTextureTransferInfo;
import org.lwjgl.sdl.SDL_GPUTransferBufferCreateInfo;

/** A native Metal clear/readback check using Angelica's actual device implementation. */
public final class OffscreenProbe {
    private static final int SIZE = 32;
    private static final int BYTE_COUNT = SIZE * SIZE * 4;
    private static final int EXPECTED = 0xFF20C060;

    private OffscreenProbe() { }

    public static void run(Path destination) throws IOException {
        Path output = destination.toAbsolutePath();
        Files.createDirectories(output);
        Configuration.SHARED_LIBRARY_EXTRACT_PATH.set(output.resolve("natives").toString());
        if (!System.getProperty("os.name").equals("Mac OS X")) {
            throw new IllegalStateException("This first graphics probe specifically requires Metal on macOS");
        }
        long mainThread = LibSystem.getLibrary().getFunctionAddress("pthread_main_np");
        if (mainThread == 0 || JNI.invokeI(mainThread) == 0) {
            throw new IllegalStateException("SDL video initialization requires the Cocoa main thread; current thread: "
                    + Thread.currentThread().getName());
        }
        SDLHints.SDL_SetHint(SDLHints.SDL_HINT_MAC_BACKGROUND_APP, "1");
        if (!SDLInit.SDL_Init(SDLInit.SDL_INIT_VIDEO)) throw failure("SDL_Init");
        var device = new Device();
        try {
            if (!device.createDevice()) throw failure("Angelica Device.createDevice");
            if (!device.isMetal()) throw new IllegalStateException("Expected Metal, got " + device.getDriverName());
            byte[] rgba = clearAndRead(device);
            verify(rgba);
            var image = new BufferedImage(SIZE, SIZE, BufferedImage.TYPE_INT_ARGB);
            for (int y = 0; y < SIZE; y++) {
                for (int x = 0; x < SIZE; x++) image.setRGB(x, y, argb(rgba, (y * SIZE + x) * 4));
            }
            if (!ImageIO.write(image, "png", output.resolve("metal-clear.png").toFile())) {
                throw new IOException("No PNG writer available");
            }
            String result = "PASS\nbackend=" + device.getDriverName() + "\ngpu=" + device.getDeviceName()
                    + "\nprocess_id=" + ProcessHandle.current().pid() + "\nsdl=" + SDLVersion.SDL_GetVersion() + "\nsize=" + SIZE + "x" + SIZE
                    + "\nverified_pixels=" + SIZE * SIZE + "\nargb=FF20C060\nthread="
                    + Thread.currentThread().getName() + "\n";
            Files.writeString(output.resolve("result.txt"), result);
            System.out.println("[Julia] PASS: Angelica / Metal / " + device.getDeviceName()
                    + "; " + SIZE * SIZE + " offscreen pixels verified; " + output);
        } finally {
            device.destroyDevice();
            SDLInit.SDL_Quit();
        }
    }

    private static byte[] clearAndRead(Device owner) {
        long device = owner.getDevice();
        long texture = 0;
        long download = 0;
        long commands = 0;
        long fence = 0;
        try (var stack = MemoryStack.stackPush()) {
            texture = require(SDL_CreateGPUTexture(device, SDL_GPUTextureCreateInfo.calloc(stack)
                    .type(SDL_GPU_TEXTURETYPE_2D).format(SDL_GPU_TEXTUREFORMAT_R8G8B8A8_UNORM)
                    .usage(SDL_GPU_TEXTUREUSAGE_COLOR_TARGET).width(SIZE).height(SIZE)
                    .layer_count_or_depth(1).num_levels(1).sample_count(SDL_GPU_SAMPLECOUNT_1)), "Create texture");
            download = require(SDL_CreateGPUTransferBuffer(device, SDL_GPUTransferBufferCreateInfo.calloc(stack)
                    .usage(SDL_GPU_TRANSFERBUFFERUSAGE_DOWNLOAD).size(BYTE_COUNT)), "Create readback buffer");
            commands = require(SDL_AcquireGPUCommandBuffer(device), "Acquire command buffer");
            var targets = SDL_GPUColorTargetInfo.calloc(1, stack);
            targets.get(0).texture(texture).load_op(SDL_GPU_LOADOP_CLEAR).store_op(SDL_GPU_STOREOP_STORE)
                    .clear_color(color -> color.r(32 / 255.0f).g(192 / 255.0f).b(96 / 255.0f).a(1));
            long render = require(SDL_BeginGPURenderPass(commands, targets, null), "Begin render pass");
            SDL_EndGPURenderPass(render);
            long copy = require(SDL_BeginGPUCopyPass(commands), "Begin copy pass");
            SDL_DownloadFromGPUTexture(copy, SDL_GPUTextureRegion.calloc(stack)
                            .texture(texture).w(SIZE).h(SIZE).d(1),
                    SDL_GPUTextureTransferInfo.calloc(stack).transfer_buffer(download)
                            .pixels_per_row(SIZE).rows_per_layer(SIZE));
            SDL_EndGPUCopyPass(copy);
            fence = SDL_SubmitGPUCommandBufferAndAcquireFence(commands);
            commands = 0;
            require(fence, "Submit command buffer");
            long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
            while (!(SDL_QueryGPUFence(device, fence) ^ owner.isFenceQueryInverted())) {
                if (System.nanoTime() >= deadline) throw new IllegalStateException("Metal readback fence timed out");
                java.util.concurrent.locks.LockSupport.parkNanos(1_000_000);
            }
            ByteBuffer mapped = SDL_MapGPUTransferBuffer(device, download, false, BYTE_COUNT);
            if (mapped == null) throw failure("Map readback buffer");
            try {
                byte[] pixels = new byte[BYTE_COUNT];
                mapped.get(pixels);
                return pixels;
            } finally {
                SDL_UnmapGPUTransferBuffer(device, download);
            }
        } finally {
            if (commands != 0) SDL_CancelGPUCommandBuffer(commands);
            if (fence != 0) SDL_ReleaseGPUFence(device, fence);
            if (download != 0) SDL_ReleaseGPUTransferBuffer(device, download);
            if (texture != 0) SDL_ReleaseGPUTexture(device, texture);
        }
    }

    private static void verify(byte[] pixels) {
        for (int offset = 0; offset < pixels.length; offset += 4) {
            int actual = argb(pixels, offset);
            if (actual != EXPECTED) throw new IllegalStateException("Pixel " + offset / 4
                    + " expected " + Integer.toHexString(EXPECTED) + ", got " + Integer.toHexString(actual));
        }
    }

    private static int argb(byte[] pixels, int offset) {
        return (pixels[offset + 3] & 255) << 24 | (pixels[offset] & 255) << 16
                | (pixels[offset + 1] & 255) << 8 | pixels[offset + 2] & 255;
    }

    private static long require(long handle, String operation) {
        if (handle == 0) throw failure(operation);
        return handle;
    }

    private static IllegalStateException failure(String operation) {
        return new IllegalStateException(operation + ": " + SDLError.SDL_GetError());
    }
}
