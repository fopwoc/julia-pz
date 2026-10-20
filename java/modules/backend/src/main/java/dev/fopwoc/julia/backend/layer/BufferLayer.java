package dev.fopwoc.julia.backend.layer;

import static org.lwjgl.opengl.GL12.*;
import static org.lwjgl.opengl.GL15.GL_ELEMENT_ARRAY_BUFFER;
import static org.lwjgl.opengl.GL30.*;
import static org.lwjgl.opengl.GL31.GL_TEXTURE_BUFFER;

import dev.fopwoc.julia.backend.Upstream;
import dev.fopwoc.julia.backend.buffer.IndexBufferUploads;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;

/** Buffer object names, buffer-texture bindings, and index data retained for quad conversion. */
public abstract class BufferLayer extends VertexLayer {
    @Override
    public void bindBuffer(int target, int name) {
        if (target == GL_TEXTURE_BUFFER) subsystems().bufferTextures().bindBuffer(name);
        else super.bindBuffer(target, name);
        if (name != 0 && Upstream.context().getBoundBuffer(target) == name)
            subsystems().bufferObjects().add(name);
    }

    @Override
    public void bufferSubData(int target, long offset, ByteBuffer data) {
        endComputeDispatchBatch();
        subsystems().bufferTextures().beforeSubData(Upstream.context().getBoundBuffer(target));
        super.bufferSubData(target, offset, data);
    }

    @Override
    public int createBuffers() {
        int name = super.createBuffers();
        subsystems().bufferObjects().add(name);
        return name;
    }

    @Override
    public boolean isBuffer(int name) {
        return subsystems().bufferObjects().contains(name) || super.isBuffer(name);
    }

    @Override
    public void deleteBuffers(int name) {
        subsystems().bufferObjects().remove(name);
        subsystems().bufferTextures().deletedBuffer(name);
        super.deleteBuffers(name);
    }

    @Override
    public void deleteBuffers(IntBuffer names) {
        for (int i = names.position(); i < names.limit(); i++) deleteBuffers(names.get(i));
    }

    @Override
    public void bufferData(int target, ByteBuffer data, int usage) {
        int name = target == GL_ELEMENT_ARRAY_BUFFER ? Upstream.context().getBoundBuffer(target) : 0;
        boolean remember = name != 0 && data != null && data.hasRemaining() && !isBufferImmutable(name);
        super.bufferData(target, data, usage);
        if (remember) IndexBufferUploads.remember(name, data);
    }
}
