package dev.fopwoc.julia.backend.shader;

import static org.lwjgl.util.spvc.Spv.*;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import org.lwjgl.opengl.GL20;

/** Metal includes base instance in instance_id; subtract it without changing OpenGL's vertex ID. */
public final class InstanceIndexSpirv {
    private InstanceIndexSpirv() {}

    public static ByteBuffer translate(ByteBuffer source, int stage) {
        if (stage != GL20.GL_VERTEX_SHADER) return source;
        var input = source.duplicate().order(ByteOrder.LITTLE_ENDIAN).asIntBuffer();
        int[] words = new int[input.remaining()];
        input.get(words);
        if (words.length < 5 || words[0] != SpvMagicNumber) throw new IllegalArgumentException("Invalid vertex SPIR-V");
        int instance = 0, base = 0;
        boolean capability = false, loaded = false;
        var pointers = new HashMap<Integer, Integer>();
        var variables = new HashMap<Integer, Integer>();
        var integerTypes = new HashSet<Integer>();
        for (int at = 5; at < words.length; at += length(words, at)) {
            int op = words[at] & 65535;
            if (op == SpvOpDecorate && words[at + 2] == SpvDecorationBuiltIn) {
                int builtin = words[at + 3];
                if (builtin == SpvBuiltInInstanceId || builtin == SpvBuiltInInstanceIndex) instance = words[at + 1];
                if (builtin == SpvBuiltInBaseInstance) base = words[at + 1];
            }
            if (op == SpvOpTypePointer) pointers.put(words[at + 1], words[at + 3]);
            if (op == SpvOpTypeInt && words[at + 2] == 32) integerTypes.add(words[at + 1]);
            if (op == SpvOpVariable) variables.put(words[at + 2], words[at + 1]);
            if (op == SpvOpCapability && words[at + 1] == SpvCapabilityDrawParameters) capability = true;
        }
        if (instance == 0) return source;
        for (int at = 5; at < words.length; at += length(words, at))
            if ((words[at] & 65535) == SpvOpLoad && words[at + 3] == instance) loaded = true;
        if (!loaded) return source;
        int pointerType = variables.getOrDefault(instance, 0), scalar = pointers.getOrDefault(pointerType, 0);
        if (!integerTypes.contains(scalar))
            throw new UnsupportedOperationException("Instance index must be a 32-bit integer");
        boolean createBase = base == 0;
        int next = words[3];
        if (createBase) base = next++;
        int baseScalar = createBase ? scalar : pointers.getOrDefault(variables.getOrDefault(base, 0), 0);
        if (!integerTypes.contains(baseScalar))
            throw new UnsupportedOperationException("Base instance must be a 32-bit integer");
        var output = new ArrayList<Integer>(words.length + 64);
        for (int i = 0; i < 5; i++) output.add(words[i]);
        boolean prologue = false, decorated = false, declared = false;
        for (int at = 5; at < words.length; at += length(words, at)) {
            int op = words[at] & 65535, size = length(words, at);
            if (!prologue && op != SpvOpCapability) {
                if (!capability) instruction(output, SpvOpCapability, SpvCapabilityDrawParameters);
                byte[] extension = "SPV_KHR_shader_draw_parameters\0".getBytes(StandardCharsets.US_ASCII);
                var encoded =
                        ByteBuffer.allocate((extension.length + 3) / 4 * 4).order(ByteOrder.LITTLE_ENDIAN);
                encoded.put(extension).clear();
                int[] text = new int[encoded.capacity() / 4];
                encoded.asIntBuffer().get(text);
                instruction(output, SpvOpExtension, text);
                prologue = true;
            }
            if (createBase && !decorated && op >= SpvOpTypeVoid && op <= SpvOpTypeForwardPointer) {
                instruction(output, SpvOpDecorate, base, SpvDecorationBuiltIn, SpvBuiltInBaseInstance);
                decorated = true;
            }
            if (createBase && !declared && op == SpvOpFunction) {
                instruction(output, SpvOpVariable, pointerType, base, SpvStorageClassInput);
                declared = true;
            }
            if (createBase && op == SpvOpEntryPoint && words[at + 1] == SpvExecutionModelVertex) {
                output.add(((size + 1) << 16) | op);
                for (int i = 1; i < size; i++) output.add(words[at + i]);
                output.add(base);
            } else if (op == SpvOpLoad && words[at + 3] == instance) {
                int value = next++, baseValue = next++;
                for (int i = 0; i < size; i++) output.add(i == 2 ? value : words[at + i]);
                instruction(output, SpvOpLoad, baseScalar, baseValue, base);
                if (baseScalar != scalar) {
                    int converted = next++;
                    instruction(output, SpvOpBitcast, scalar, converted, baseValue);
                    baseValue = converted;
                }
                instruction(output, SpvOpISub, scalar, words[at + 2], value, baseValue);
            } else for (int i = 0; i < size; i++) output.add(words[at + i]);
        }
        if (createBase && (!declared || !decorated))
            throw new IllegalStateException("Cannot declare base-instance shader input");
        output.set(3, next);
        var result =
                ByteBuffer.allocateDirect(Math.multiplyExact(output.size(), 4)).order(ByteOrder.LITTLE_ENDIAN);
        for (int word : output) result.putInt(word);
        return result.flip();
    }

    private static int length(int[] words, int at) {
        int length = words[at] >>> 16;
        if (length == 0 || length > words.length - at)
            throw new IllegalArgumentException("Malformed SPIR-V instruction");
        return length;
    }

    private static void instruction(ArrayList<Integer> output, int op, int... operands) {
        output.add(((operands.length + 1) << 16) | op);
        for (int operand : operands) output.add(operand);
    }
}
