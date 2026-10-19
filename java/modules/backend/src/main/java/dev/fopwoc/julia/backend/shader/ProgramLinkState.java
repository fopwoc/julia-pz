package dev.fopwoc.julia.backend.shader;

import com.gtnewhorizons.angelica.sdlgpu.frame.ContextState;
import com.gtnewhorizons.angelica.sdlgpu.shader.ShaderManager;
import java.util.Arrays;

/** Uniform locations, values and resource assignments belong to one linked executable. */
public final class ProgramLinkState {
    private ProgramLinkState() {}

    public static void reset(ShaderManager.ProgramObject program) {
        program.nameToLocation.clear();
        program.locationToName.clear();
        program.locationToMemberInfo.clear();
        program.vertexMemberInfo.clear();
        program.fragmentMemberInfo.clear();
        for (var block : program.blockMemberInfo) block.clear();
        Arrays.fill(program.blockSize, 0);
        Arrays.fill(program.blockBinding, -1);
        program.vertexUboSize = 0;
        program.fragmentUboSize = 0;
        program.externalUboBinding = -1;
        program.nextUniformLocation = 0;
        program.activeUniformNames = null;
        program.samplerTextureUnits.clear();
        program.imageTextureUnits.clear();
    }

    public static void linked(ShaderManager.ProgramObject program, ContextState state) {
        for (int location = 0; location < program.uniformSlotCount; location++)
            if (program.locationKind[location] == ShaderManager.ProgramObject.LOCATION_KIND_SAMPLER)
                program.locationSamplerUnit[location] = 0;
        state.lastPushedCbVs = 0;
        state.lastPushedCbFs = 0;
        state.lastAppliedSamplerProgram = 0;
        state.lastFragSamplerProgram = 0;
        state.lastVertSamplerProgram = 0;
        state.lastAppliedVboBindProgram = 0;
        state.lastAppliedStorageTexProgram = 0;
        state.lastFragStorageTexProgram = 0;
        state.lastVertStorageTexProgram = 0;
        state.lastAppliedStorageBufProgram = 0;
        state.lastFragStorageBufProgram = 0;
        state.lastVertStorageBufProgram = 0;
    }
}
