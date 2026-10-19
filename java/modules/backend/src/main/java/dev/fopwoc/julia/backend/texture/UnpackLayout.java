package dev.fopwoc.julia.backend.texture;

import com.gtnewhorizons.angelica.sdlgpu.frame.ContextState.PixelStoreState;

/** Checked byte addressing for padded rows and images; the final row needs no trailing padding. */
public record UnpackLayout(long start, long rowStride, long imageStride, int requiredBytes) {
    public static UnpackLayout of(
            int width, int height, int depth, int bytes, PixelStoreState state, int imageHeight, int skipImages) {
        if (width < 0
                || height < 0
                || depth < 0
                || bytes <= 0
                || imageHeight < 0
                || skipImages < 0
                || state.unpackRowLength < 0
                || state.unpackSkipRows < 0
                || state.unpackSkipPixels < 0)
            throw new IllegalArgumentException("Invalid pixel unpack dimensions or skips");
        int alignment = state.unpackAlignment;
        if (alignment != 1 && alignment != 2 && alignment != 4 && alignment != 8)
            throw new IllegalArgumentException("Invalid unpack alignment");
        if (width == 0 || height == 0 || depth == 0) return new UnpackLayout(0, 0, 0, 0);
        long row = Math.multiplyExact((long) (state.unpackRowLength == 0 ? width : state.unpackRowLength), bytes);
        row = Math.multiplyExact(Math.addExact(row, alignment - 1) / alignment, alignment);
        long image = Math.multiplyExact(row, imageHeight == 0 ? height : imageHeight);
        long start = Math.addExact(
                Math.multiplyExact(image, skipImages),
                Math.addExact(
                        Math.multiplyExact(row, state.unpackSkipRows),
                        Math.multiplyExact((long) state.unpackSkipPixels, bytes)));
        long span = Math.addExact(
                start,
                Math.addExact(
                        Math.multiplyExact(image, depth - 1),
                        Math.addExact(Math.multiplyExact(row, height - 1), Math.multiplyExact((long) width, bytes))));
        return new UnpackLayout(start, row, image, Math.toIntExact(span));
    }
}
