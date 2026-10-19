package dev.fopwoc.julia.backend.texture.buffer;

/** Native views share physical buffer storage and retain it until queued texture reads complete. */
public interface BufferTextureDriver {
    int limit();

    int alignment(BufferTextureFormat format);

    long identity(long buffer);

    long create(long buffer, BufferTextureFormat format, int offset, int texels);

    void release(long texture);
}
