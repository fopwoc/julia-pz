package dev.julia.probe.adapter;

import com.gtnewhorizons.angelica.sdlgpu.shader.ShaderManager;
import com.gtnewhorizons.angelica.sdlgpu.shader.UniformStaging;
import org.junit.jupiter.api.Test;
import org.lwjgl.util.spvc.Spvc;
import static org.junit.jupiter.api.Assertions.*;

class UniformStateTest {
    @Test void readsOneArrayElementAndMatrixColumnOrderFromActualStagingShape() {
        var program = program(2, 1, 2);
        var staging = staging(new float[] {0.25f, 0.75f, 9, 8});
        assertArrayEquals(new float[] {0.25f, 0.75f}, UniformState.read(program, staging, 0).raw());
        program.locationToMemberInfo.put(0, new ShaderManager.UniformMemberInfo(0, 64, 0, false, 4, 4, Spvc.SPVC_BASETYPE_FP32, 0));
        float[] matrix = new float[16];
        for (int i = 0; i < matrix.length; i++) matrix[i] = i;
        assertArrayEquals(matrix, UniformState.read(program, staging(matrix), 0).raw());
    }

    @Test void distinguishesDefaultZeroAndInvalidOrIncompleteUploads() {
        var program = program(2, 1, 0);
        assertArrayEquals(new float[] {0, 0}, UniformState.read(program, staging(null), 0).raw());
        assertThrows(IllegalStateException.class, () -> UniformState.read(program, staging(new float[] {1}), 0));
        assertThrows(IllegalArgumentException.class, () -> UniformState.read(program, staging(null), 1));
        program.linked = false;
        assertThrows(IllegalArgumentException.class, () -> UniformState.read(program, staging(null), 0));
    }

    @Test void readsSamplerAssignmentsOutsideOrdinaryUniformStorage() {
        var program = program(1, 1, 0);
        program.locationKind = new byte[] {ShaderManager.ProgramObject.LOCATION_KIND_SAMPLER};
        program.locationSamplerUnit = new int[] {7};
        assertEquals(7, UniformState.read(program, staging(new float[] {99}), 0).scalarInt());
    }

    private static ShaderManager.ProgramObject program(int vector, int columns, int arrayLength) {
        var program = new ShaderManager.ProgramObject();
        program.linked = true;
        program.uniformSlotCount = 1;
        program.locationToName.put(0, "value");
        program.locationToMemberInfo.put(0, new ShaderManager.UniformMemberInfo(0, 64, 0, false, vector, columns, Spvc.SPVC_BASETYPE_FP32, arrayLength));
        return program;
    }

    private static UniformStaging staging(float[] values) {
        var staging = new UniformStaging();
        staging.uniformDataBySlot = new float[][] {values};
        return staging;
    }
}
