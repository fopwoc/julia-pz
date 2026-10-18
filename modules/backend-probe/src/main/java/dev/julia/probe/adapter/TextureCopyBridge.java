package dev.julia.probe.adapter;

import com.gtnewhorizons.angelica.sdlgpu.resource.ResourceManager;
import org.lwjgl.sdl.SDL_GPUTextureLocation;
import org.lwjgl.system.MemoryStack;
import static org.lwjgl.sdl.SDLGPU.SDL_CopyGPUTextureToTexture;

/** Bit-preserving copies with array layers distinguished from volume slices. */
public final class TextureCopyBridge {
    private TextureCopyBridge() { }
    public static void glCopyImageSubData(int source, int sourceTarget, int sourceLevel, int sx, int sy, int sz,
                                          int destination, int destinationTarget, int destinationLevel, int dx, int dy, int dz,
                                          int width, int height, int depth) {
        var resources = ResourceStateAccess.resources();
        var frames = ResourceStateAccess.frames();
        var sourceMeta = resources.getTextureMeta(source);
        var destinationMeta = resources.getTextureMeta(destination);
        validate(sourceMeta, sourceTarget, new ClearRegion(sourceLevel, sx, sy, sz, width, height, depth));
        validate(destinationMeta, destinationTarget, new ClearRegion(destinationLevel, dx, dy, dz, width, height, depth));
        if (sourceMeta.sdlFormat() != destinationMeta.sdlFormat()) throw new UnsupportedOperationException("Image copy requires identical SDL storage formats");
        if (width == 0 || height == 0 || depth == 0) return;
        long sourceHandle = resources.getTextureHandle(source);
        long destinationHandle = resources.getTextureHandle(destination);
        if (sourceHandle == 0 || destinationHandle == 0) throw new IllegalArgumentException("Image copy requires allocated textures");
        if (sourceHandle == destinationHandle && sourceLevel == destinationLevel
                && overlaps(sx, dx, width) && overlaps(sy, dy, height) && overlaps(sz, dz, depth)) {
            throw new UnsupportedOperationException("Overlapping image copy");
        }
        if (frames.getCommandBuffer() == 0) throw new IllegalStateException("Image copy requires an active frame");
        var clears = ResourceStateAccess.clears();
        var context = ShaderStateAccess.context();
        clears.materializePendingClearForTexture(context, sourceHandle);
        clears.resolveDestinationForWrite(context, destinationHandle, destinationMeta, destinationLevel, dx, dy, dz, width, height, false);
        long copy = frames.ensureCopyPass();
        if (copy == 0) throw new IllegalStateException("Cannot acquire image-copy pass");
        resources.flushBatchedUploads(copy);
        try (var stack = MemoryStack.stackPush()) {
            var src = SDL_GPUTextureLocation.calloc(stack).texture(sourceHandle).mip_level(sourceLevel).x(sx).y(sy);
            var dst = SDL_GPUTextureLocation.calloc(stack).texture(destinationHandle).mip_level(destinationLevel).x(dx).y(dy);
            for (int layer = 0; layer < depth; layer++) {
                src.layer(sourceTarget == 0x8C1A ? sz + layer : 0).z(sourceTarget == 0x8C1A ? 0 : sz + layer);
                dst.layer(destinationTarget == 0x8C1A ? dz + layer : 0).z(destinationTarget == 0x8C1A ? 0 : dz + layer);
                SDL_CopyGPUTextureToTexture(copy, src, dst, width, height, 1, false);
            }
            resources.markTextureWritten(destinationHandle);
        }
    }

    private static boolean overlaps(int first, int second, int length) {
        return (long) first < (long) second + length && (long) second < (long) first + length;
    }
    private static void validate(ResourceManager.TextureMeta meta, int target, ClearRegion region) {
        if (meta == null || meta.glTarget() != target) throw new IllegalArgumentException("Image copy target does not match storage");
        if (target != 0x0DE1 && target != 0x8C1A && target != 0x806F) throw new UnsupportedOperationException("Unsupported image-copy target");
        int shift = Math.clamp(region.level(), 0, 30);
        region.validate(meta.levels(), Math.max(1, meta.width() >> shift), Math.max(1, meta.height() >> shift),
                target == 0x8C1A ? meta.depth() : Math.max(1, meta.depth() >> shift));
    }
}
