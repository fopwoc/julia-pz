package dev.julia.backend.bridge;

import com.gtnewhorizons.angelica.sdlgpu.SDLGPURenderBackend;
import java.util.Set;

/** Backend-advertised flags only; never exports native capabilities or function pointers. */
public final class CapabilityBridge {
    private static final Set<String> ADVERTISED = Set.copyOf(SDLGPURenderBackend.advertisedCapabilities());
    private CapabilityBridge() { }
    public static Set<String> current() { return ADVERTISED; }
}
