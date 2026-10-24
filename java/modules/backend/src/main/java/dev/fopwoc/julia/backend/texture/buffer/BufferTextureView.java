package dev.fopwoc.julia.backend.texture.buffer;

/** One buffer-texture attachment; owned by {@link BufferTextures}, carried on the texture name's record. */
public final class BufferTextureView {
    int buffer;
    BufferTextureFormat format;
    long offset, size, bufferHandle, identity, texture;
    boolean whole, deleted;
    long readEpoch = -1;
    int texels;

    BufferTextureView() {}
}
