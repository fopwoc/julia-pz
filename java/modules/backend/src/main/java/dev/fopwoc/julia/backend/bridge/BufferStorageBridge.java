package dev.fopwoc.julia.backend.bridge;

import static org.lwjgl.opengl.GL15.GL_ARRAY_BUFFER;
import static org.lwjgl.opengl.GL15.GL_ELEMENT_ARRAY_BUFFER;
import static org.lwjgl.opengl.GL21.GL_PIXEL_PACK_BUFFER;
import static org.lwjgl.opengl.GL21.GL_PIXEL_UNPACK_BUFFER;
import static org.lwjgl.opengl.GL30.GL_MAP_READ_BIT;
import static org.lwjgl.opengl.GL30.GL_MAP_WRITE_BIT;
import static org.lwjgl.opengl.GL31.GL_COPY_READ_BUFFER;
import static org.lwjgl.opengl.GL31.GL_COPY_WRITE_BUFFER;
import static org.lwjgl.opengl.GL31.GL_UNIFORM_BUFFER;
import static org.lwjgl.opengl.GL40.GL_DRAW_INDIRECT_BUFFER;
import static org.lwjgl.opengl.GL43.GL_DISPATCH_INDIRECT_BUFFER;
import static org.lwjgl.opengl.GL43.GL_SHADER_STORAGE_BUFFER;
import static org.lwjgl.opengl.GL44.GL_CLIENT_STORAGE_BIT;
import static org.lwjgl.opengl.GL44.GL_DYNAMIC_STORAGE_BIT;
import static org.lwjgl.opengl.GL44.GL_MAP_COHERENT_BIT;
import static org.lwjgl.opengl.GL44.GL_MAP_PERSISTENT_BIT;

import com.gtnewhorizons.angelica.glsm.GLStateManager;
import com.gtnewhorizons.angelica.glsm.backend.BackendManager;
import dev.fopwoc.julia.backend.BackendInternals;
import java.nio.ByteBuffer;
import java.util.Objects;

/** Named immutable storage through the upstream storage path, with temporary state restored. */
public final class BufferStorageBridge {
    private static final int KNOWN_FLAGS = GL_MAP_READ_BIT
            | GL_MAP_WRITE_BIT
            | GL_MAP_PERSISTENT_BIT
            | GL_MAP_COHERENT_BIT
            | GL_DYNAMIC_STORAGE_BIT
            | GL_CLIENT_STORAGE_BIT;
    private static final int[] BINDING_TARGETS = {
        GL_ARRAY_BUFFER,
        GL_ELEMENT_ARRAY_BUFFER,
        GL_UNIFORM_BUFFER,
        GL_PIXEL_PACK_BUFFER,
        GL_PIXEL_UNPACK_BUFFER,
        GL_DRAW_INDIRECT_BUFFER,
        GL_DISPATCH_INDIRECT_BUFFER,
        GL_COPY_READ_BUFFER,
        GL_COPY_WRITE_BUFFER,
        GL_SHADER_STORAGE_BUFFER
    };

    private BufferStorageBridge() {}

    public static void glNamedBufferStorage(int buffer, ByteBuffer data, int flags) {
        Objects.requireNonNull(data);
        if (!data.isDirect()) throw new IllegalArgumentException("Named buffer storage requires direct byte data");
        storage(buffer, data.remaining(), data, flags);
    }

    public static void glNamedBufferStorage(int buffer, long size, int flags) {
        storage(buffer, size, null, flags);
    }

    private static void storage(int buffer, long size, ByteBuffer data, int flags) {
        validateFlags(flags);
        if (buffer <= 0 || size <= 0) throw new IllegalArgumentException("Buffer and storage size must be positive");
        if (size > Integer.MAX_VALUE)
            throw new UnsupportedOperationException("Named buffer storage exceeds the supported byte range");
        var context = BackendInternals.context();
        boolean known = GLStateManager.glIsBuffer(buffer);
        for (int target : BINDING_TARGETS) known |= context.getBoundBuffer(target) == buffer;
        if (!known) {
            throw new UnsupportedOperationException(
                    "Named storage requires a bound or allocated upstream buffer object");
        }
        if (BackendManager.RENDER_BACKEND.isBufferImmutable(buffer)) {
            throw new IllegalStateException("Buffer storage is already immutable");
        }
        int previous = context.boundCopyWriteBuffer;
        try {
            GLStateManager.glBindBuffer(GL_COPY_WRITE_BUFFER, buffer);
            if (data == null) GLStateManager.glBufferStorage(GL_COPY_WRITE_BUFFER, size, flags);
            else GLStateManager.glBufferStorage(GL_COPY_WRITE_BUFFER, data.duplicate(), flags);
            var resources = BackendInternals.resources();
            if (resources.getBufferHandle(buffer) == 0 || resources.getBufferSize(buffer) != size)
                throw new IllegalStateException("Named buffer storage allocation failed");
        } finally {
            GLStateManager.glBindBuffer(GL_COPY_WRITE_BUFFER, previous);
        }
    }

    static void validateFlags(int flags) {
        boolean unknown = (flags & ~KNOWN_FLAGS) != 0;
        boolean persistentWithoutMapping =
                (flags & GL_MAP_PERSISTENT_BIT) != 0 && (flags & (GL_MAP_READ_BIT | GL_MAP_WRITE_BIT)) == 0;
        boolean coherentWithoutPersistent = (flags & GL_MAP_COHERENT_BIT) != 0 && (flags & GL_MAP_PERSISTENT_BIT) == 0;
        if (unknown || persistentWithoutMapping || coherentWithoutPersistent) {
            throw new IllegalArgumentException("Invalid immutable storage flags: " + flags);
        }
    }
}
