package dev.fopwoc.julia.backend.shader;

import static org.junit.jupiter.api.Assertions.*;

import com.gtnewhorizons.angelica.sdlgpu.shader.ShaderManager.ProgramObject;
import com.gtnewhorizons.angelica.sdlgpu.shader.ShaderManager.UniformMemberInfo;
import java.nio.FloatBuffer;
import org.junit.jupiter.api.Test;
import org.lwjgl.util.spvc.Spvc;

class MatrixUniformUploadsTest {
    @Test
    void rejectsBonePaletteAtSingleMatrixLocation() {
        var program = program(1, 0);
        assertThrows(
                IllegalArgumentException.class,
                () -> MatrixUniformUploads.prepare(program, 0, 4, FloatBuffer.allocate(35 * 16)));
        assertThrows(
                IllegalArgumentException.class,
                () -> MatrixUniformUploads.prepare(program, 0, 3, FloatBuffer.allocate(9)));
        assertThrows(
                IllegalArgumentException.class,
                () -> MatrixUniformUploads.prepare(program, 99, 4, FloatBuffer.allocate(16)));
        program.linked = false;
        assertThrows(
                IllegalArgumentException.class,
                () -> MatrixUniformUploads.prepare(program, 0, 4, FloatBuffer.allocate(16)));
    }

    @Test
    void boundsArrayWritesWithoutChangingCallerBuffer() {
        var source = FloatBuffer.allocate(1 + 35 * 16);
        source.position(1);
        var checked = MatrixUniformUploads.prepare(program(2, 64), 0, 4, source);
        assertEquals(32, checked.remaining());
        assertEquals(1, source.position());
        assertEquals(561, source.limit());
        checked.put(0, 7);
        assertEquals(7, source.get(0));
        // An array declared with length one is still allowed a larger count; extras are ignored.
        assertEquals(
                16, MatrixUniformUploads.prepare(program(1, 64), 0, 4, source).remaining());
    }

    @Test
    void ignoresIncompleteTrailingMatrixAndZeroCount() {
        assertEquals(
                16,
                MatrixUniformUploads.prepare(program(1, 0), 0, 4, FloatBuffer.allocate(19))
                        .remaining());
        assertNull(MatrixUniformUploads.prepare(program(1, 0), 0, 4, FloatBuffer.allocate(15)));
    }

    private static ProgramObject program(int length, int stride) {
        var result = new ProgramObject();
        result.linked = true;
        result.locationToName.put(0, "transform");
        result.locationToMemberInfo.put(
                0, new UniformMemberInfo(0, 64 * length, stride, true, 4, 4, Spvc.SPVC_BASETYPE_FP32, length));
        return result;
    }
}
