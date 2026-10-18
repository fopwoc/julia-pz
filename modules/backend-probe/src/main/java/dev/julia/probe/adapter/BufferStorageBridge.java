package dev.julia.probe.adapter;

import com.gtnewhorizons.angelica.glsm.GLStateManager;
import com.gtnewhorizons.angelica.glsm.backend.BackendManager;
import java.nio.ByteBuffer;
import java.util.Objects;
import static org.lwjgl.opengl.GL31.GL_COPY_WRITE_BUFFER;

/** Named immutable storage through the upstream storage path, with temporary state restored. */
public final class BufferStorageBridge {
    private static final int KNOWN_FLAGS = 0x3C3;
    private BufferStorageBridge() { }

    static void validateFlags(int flags) {
        if ((flags & ~KNOWN_FLAGS) != 0 || (flags & 0x40) != 0 && (flags & 3) == 0
                || (flags & 0x80) != 0 && (flags & 0x40) == 0) {
            throw new IllegalArgumentException("Invalid immutable storage flags: " + flags);
        }
    }

    public static void glNamedBufferStorage(int buffer, ByteBuffer data, int flags) {
        Objects.requireNonNull(data);
        validateFlags(flags);
        if (!data.isDirect()) throw new IllegalArgumentException("Named buffer storage requires direct byte data");
        if (buffer <= 0 || !data.hasRemaining()) throw new IllegalArgumentException("Buffer and storage size must be positive");
        var context = ShaderStateAccess.context();
        boolean known = GLStateManager.glIsBuffer(buffer);
        for (int target : new int[] {0x8892, 0x8893, 0x8A11, 0x88EB, 0x88EC, 0x8F3F, 0x90EE, 0x8F36, 0x8F37, 0x90D2}) {
            known |= context.getBoundBuffer(target) == buffer;
        }
        if (!known) throw new UnsupportedOperationException("Named storage requires a bound or allocated upstream buffer object");
        if (BackendManager.RENDER_BACKEND.isBufferImmutable(buffer)) throw new IllegalStateException("Buffer storage is already immutable");
        int previous = context.boundCopyWriteBuffer;
        try {
            GLStateManager.glBindBuffer(GL_COPY_WRITE_BUFFER, buffer);
            GLStateManager.glBufferStorage(GL_COPY_WRITE_BUFFER, data.duplicate(), flags);
        } finally { GLStateManager.glBindBuffer(GL_COPY_WRITE_BUFFER, previous); }
    }
}
