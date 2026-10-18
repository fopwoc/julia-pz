package dev.julia.probe.adapter;

import com.gtnewhorizons.angelica.glsm.backend.BackendOptions;
import com.gtnewhorizons.angelica.sdlgpu.SDLGPUGate;
import com.gtnewhorizons.angelica.sdlgpu.SDLGPURenderBackend;

/** Selects the real SDL backend for an explicitly created offscreen device without a display gate. */
public final class OffscreenBackend extends SDLGPURenderBackend {
    @Override
    public boolean isAvailable() {
        return BackendOptions.sdlGpuRequested() && SDLGPUGate.device().getDevice() != 0
                && SDLGPUGate.device().getClaimedWindow() == 0;
    }
}
