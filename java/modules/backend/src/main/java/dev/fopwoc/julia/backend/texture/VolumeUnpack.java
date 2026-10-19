package dev.fopwoc.julia.backend.texture;

/** Additional client pixel-store state absent from the upstream context mirror. */
public final class VolumeUnpack {
    public int imageHeight;
    public int skipImages;
    public boolean swapBytes;
}
