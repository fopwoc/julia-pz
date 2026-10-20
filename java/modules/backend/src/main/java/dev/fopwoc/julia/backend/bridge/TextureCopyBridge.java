package dev.fopwoc.julia.backend.bridge;

import static org.lwjgl.sdl.SDLGPU.SDL_CopyGPUTextureToTexture;

import com.gtnewhorizons.angelica.sdlgpu.resource.ResourceManager;
import dev.fopwoc.julia.backend.Upstream;
import org.lwjgl.sdl.SDL_GPUTextureLocation;
import org.lwjgl.system.MemoryStack;

/** Bit-preserving copies with array layers distinguished from volume slices. */
public final class TextureCopyBridge {
    private TextureCopyBridge() {}

    public static void glCopyImageSubData(
            int source,
            int sourceTarget,
            int sourceLevel,
            int sx,
            int sy,
            int sz,
            int destination,
            int destinationTarget,
            int destinationLevel,
            int dx,
            int dy,
            int dz,
            int width,
            int height,
            int depth) {
        var resources = Upstream.resources();
        var frames = Upstream.frames();
        var sourceMeta = resources.getTextureMeta(source);
        var destinationMeta = resources.getTextureMeta(destination);
        validate(sourceMeta, sourceTarget, new TextureRegion(sourceLevel, sx, sy, sz, width, height, depth));
        validate(
                destinationMeta,
                destinationTarget,
                new TextureRegion(destinationLevel, dx, dy, dz, width, height, depth));
        if (sourceMeta.sdlFormat() != destinationMeta.sdlFormat()) {
            throw new UnsupportedOperationException("Image copy requires identical SDL storage formats");
        }
        if (width == 0 || height == 0 || depth == 0) return;

        long sourceHandle = resources.getTextureHandle(source);
        long destinationHandle = resources.getTextureHandle(destination);
        if (sourceHandle == 0 || destinationHandle == 0) {
            throw new IllegalArgumentException("Image copy requires allocated textures");
        }
        if (sourceHandle == destinationHandle
                && sourceLevel == destinationLevel
                && overlaps(sx, dx, width)
                && overlaps(sy, dy, height)
                && overlaps(sz, dz, depth)) {
            throw new UnsupportedOperationException("Overlapping image copy");
        }
        if (frames.getCommandBuffer() == 0) throw new IllegalStateException("Image copy requires an active frame");

        var clears = Upstream.clears();
        var context = Upstream.context();
        clears.materializePendingClearForTexture(context, sourceHandle);
        clears.resolveDestinationForWrite(
                context, destinationHandle, destinationMeta, destinationLevel, dx, dy, dz, width, height, false);
        long copy = frames.ensureCopyPass();
        if (copy == 0) throw new IllegalStateException("Cannot acquire image-copy pass");
        resources.flushBatchedUploads(copy);
        try (var stack = MemoryStack.stackPush()) {
            var from = SDL_GPUTextureLocation.calloc(stack)
                    .texture(sourceHandle)
                    .mip_level(sourceLevel)
                    .x(sx)
                    .y(sy);
            var to = SDL_GPUTextureLocation.calloc(stack)
                    .texture(destinationHandle)
                    .mip_level(destinationLevel)
                    .x(dx)
                    .y(dy);
            boolean sourceArray = TextureRegion.isArray(sourceTarget);
            boolean destinationArray = TextureRegion.isArray(destinationTarget);
            for (int layer = 0; layer < depth; layer++) {
                from.layer(sourceArray ? sz + layer : 0).z(sourceArray ? 0 : sz + layer);
                to.layer(destinationArray ? dz + layer : 0).z(destinationArray ? 0 : dz + layer);
                SDL_CopyGPUTextureToTexture(copy, from, to, width, height, 1, false);
            }
            resources.markTextureWritten(destinationHandle);
        }
    }

    private static boolean overlaps(int first, int second, int length) {
        return (long) first < (long) second + length && (long) second < (long) first + length;
    }

    private static void validate(ResourceManager.TextureMeta meta, int target, TextureRegion region) {
        if (meta == null || meta.glTarget() != target) {
            throw new IllegalArgumentException("Image copy target does not match storage");
        }
        if (!TextureRegion.isSupported(target))
            throw new UnsupportedOperationException("Unsupported image-copy target");
        region.validate(meta);
    }
}
