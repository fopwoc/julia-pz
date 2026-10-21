package dev.fopwoc.julia.probe.device;

import dev.fopwoc.julia.backend.utils.Sdl;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.lwjgl.sdl.SDL;
import org.lwjgl.system.JNI;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

/** Audits the selected device's extension table without invoking any backend-specific resource operation. */
final class ExtensionReport {
    private ExtensionReport() {}

    static void write(long device, Path output) throws IOException {
        long lookup = SDL.getLibrary().getFunctionAddress("JULIA_SDL_GetExtension");
        if (lookup == 0) throw new IllegalStateException("The SDL library lacks Julia's device extension dispatcher");
        String[] names = {
            "VertexInput",
            "TextureView",
            "Timestamp",
            "Visibility",
            "BufferTexture",
            "SparseColorTargets",
            "SamplerArguments"
        };
        int[] versions = {2, 1, 1, 1, 1, 1, 2};
        int supported = 0;
        var report = new StringBuilder();
        try (var stack = MemoryStack.stackPush()) {
            long unknown =
                    JNI.invokePPP(device, MemoryUtil.memAddress(stack.UTF8("JULIA_SDL_UnknownExtension")), lookup);
            if (unknown != 0) throw new IllegalStateException("Unknown native extension unexpectedly resolved");
            for (int at = 0; at < names.length; at++) {
                String symbol = "JULIA_SDL_" + names[at] + "ABI";
                long address = JNI.invokePPP(device, MemoryUtil.memAddress(stack.UTF8(symbol)), lookup);
                if (address == 0) {
                    report.append(names[at]).append("=UNAVAILABLE\n");
                } else {
                    int version = JNI.invokeI(address);
                    if (version != versions[at]) throw Sdl.failure("Unexpected " + symbol + " version " + version);
                    supported++;
                    report.append(names[at]).append("=ABI ").append(version).append('\n');
                }
            }
        }
        Files.writeString(
                output.resolve("extensions.txt"), "supported=" + supported + "/" + names.length + "\n" + report);
    }
}
