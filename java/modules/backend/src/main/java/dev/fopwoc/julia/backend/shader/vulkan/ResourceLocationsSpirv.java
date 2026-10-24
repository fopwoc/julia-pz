package dev.fopwoc.julia.backend.shader.vulkan;

import static org.lwjgl.util.spvc.Spv.*;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.HashSet;

/** Removes GL uniform locations only from native Vulkan code, retaining the original code for GL reflection. */
public final class ResourceLocationsSpirv {
    private ResourceLocationsSpirv() {}

    public static ByteBuffer translate(ByteBuffer source) {
        var input = source.duplicate().order(ByteOrder.LITTLE_ENDIAN).asIntBuffer();
        int[] words = new int[input.remaining()];
        input.get(words);
        if (source.remaining() % 4 != 0 || words.length < 5 || words[0] != SpvMagicNumber)
            throw new IllegalArgumentException("Invalid Vulkan SPIR-V");
        var resources = new HashSet<Integer>();
        for (int at = 5; at < words.length; at += length(words, at)) {
            int size = length(words, at);
            if ((words[at] & 65535) == SpvOpVariable && size >= 4 && words[at + 3] == SpvStorageClassUniformConstant)
                resources.add(words[at + 2]);
        }
        int removed = 0;
        for (int at = 5; at < words.length; at += length(words, at))
            if (resourceLocation(words, at, resources)) removed += length(words, at);
        if (removed == 0) return source;
        var output = ByteBuffer.allocateDirect((words.length - removed) * 4).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < 5; i++) output.putInt(words[i]);
        for (int at = 5; at < words.length; at += length(words, at))
            if (!resourceLocation(words, at, resources))
                for (int i = 0; i < length(words, at); i++) output.putInt(words[at + i]);
        return output.flip();
    }

    private static boolean resourceLocation(int[] words, int at, HashSet<Integer> resources) {
        return (words[at] & 65535) == SpvOpDecorate
                && length(words, at) == 4
                && words[at + 2] == SpvDecorationLocation
                && resources.contains(words[at + 1]);
    }

    private static int length(int[] words, int at) {
        int length = words[at] >>> 16;
        if (length == 0 || length > words.length - at)
            throw new IllegalArgumentException("Malformed Vulkan SPIR-V instruction");
        return length;
    }
}
