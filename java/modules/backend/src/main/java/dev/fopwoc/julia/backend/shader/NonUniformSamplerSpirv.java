package dev.fopwoc.julia.backend.shader;

import static org.lwjgl.util.spvc.Spv.*;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Marks sampler-array accesses with a non-constant index as {@code NonUniform}.
 *
 * <p>GL only defines dynamically uniform sampler-array indices, but every desktop driver also honours an index that
 * varies per primitive, and mods rely on it (ViewPoint's distant shell picks an atlas per sprite with a {@code flat}
 * index). Vulkan drivers may load one descriptor per wave without the decoration; AMD does, so neighbouring sprites
 * sample each other's atlas and alpha cut-outs turn into black boxes.
 */
public final class NonUniformSamplerSpirv {
    private static final System.Logger LOG = System.getLogger(NonUniformSamplerSpirv.class.getName());
    private static final String EXTENSION = "SPV_EXT_descriptor_indexing";
    private static volatile boolean warned;

    private NonUniformSamplerSpirv() {}

    /** @return {@code source} itself when no sampler array is indexed by a non-constant value */
    public static ByteBuffer translate(ByteBuffer source) {
        var input = source.duplicate().order(ByteOrder.LITTLE_ENDIAN).asIntBuffer();
        int[] words = new int[input.remaining()];
        input.get(words);
        if (words.length < 5 || words[0] != SpvMagicNumber) throw new IllegalArgumentException("Invalid SPIR-V");

        Set<Integer> sampledImages = new HashSet<>(), constants = new HashSet<>(), arrays = new HashSet<>();
        var arrayElements = new HashMap<Integer, Integer>();
        var pointees = new HashMap<Integer, Integer>();
        Set<Integer> samplerArrayVariables = new HashSet<>(), capabilities = new HashSet<>();
        boolean extension = false;
        for (int at = 5; at < words.length; at += length(words, at)) {
            int op = words[at] & 65535;
            switch (op) {
                case SpvOpCapability -> capabilities.add(words[at + 1]);
                case SpvOpExtension -> extension |= decode(words, at).equals(EXTENSION);
                case SpvOpTypeSampledImage -> sampledImages.add(words[at + 1]);
                // Sampled, non-buffer images; texel-buffer arrays would need a different capability.
                case SpvOpTypeImage -> {
                    if (words[at + 3] != SpvDimBuffer && words[at + 7] == 1) sampledImages.add(words[at + 1]);
                }
                case SpvOpTypeArray, SpvOpTypeRuntimeArray -> {
                    arrays.add(words[at + 1]);
                    arrayElements.put(words[at + 1], words[at + 2]);
                }
                case SpvOpTypePointer -> {
                    if (words[at + 2] == SpvStorageClassUniformConstant) pointees.put(words[at + 1], words[at + 3]);
                }
                case SpvOpConstant, SpvOpConstantTrue, SpvOpConstantFalse, SpvOpConstantNull ->
                    constants.add(words[at + 2]);
                case SpvOpVariable -> {
                    Integer pointee = pointees.get(words[at + 1]);
                    if (pointee != null
                            && arrays.contains(pointee)
                            && sampledImages.contains(arrayElements.get(pointee)))
                        samplerArrayVariables.add(words[at + 2]);
                }
                default -> {}
            }
        }
        if (samplerArrayVariables.isEmpty()) return source;

        // A sampler may reach its sample through helper-function parameters; follow those until nothing changes.
        var parameters = new HashMap<Integer, List<Integer>>();
        int function = 0;
        for (int at = 5; at < words.length; at += length(words, at)) {
            int op = words[at] & 65535;
            if (op == SpvOpFunction) parameters.put(function = words[at + 2], new ArrayList<>());
            if (op == SpvOpFunctionParameter) parameters.get(function).add(words[at + 2]);
        }
        Set<Integer> nonUniform = new HashSet<>();
        for (boolean changed = true; changed; ) {
            int before = nonUniform.size();
            for (int at = 5; at < words.length; at += length(words, at)) {
                int op = words[at] & 65535, size = length(words, at);
                if ((op == SpvOpAccessChain || op == SpvOpInBoundsAccessChain)
                        && samplerArrayVariables.contains(words[at + 3])
                        && size > 4
                        && !constants.contains(words[at + 4])) nonUniform.add(words[at + 2]);
                if ((op == SpvOpLoad || op == SpvOpCopyObject) && nonUniform.contains(words[at + 3]))
                    nonUniform.add(words[at + 2]);
                if (op == SpvOpSampledImage
                        && (nonUniform.contains(words[at + 3]) || nonUniform.contains(words[at + 4])))
                    nonUniform.add(words[at + 2]);
                if (op == SpvOpFunctionCall) {
                    var callee = parameters.getOrDefault(words[at + 3], List.of());
                    for (int argument = 4; argument < size && argument - 4 < callee.size(); argument++)
                        if (nonUniform.contains(words[at + argument])) nonUniform.add(callee.get(argument - 4));
                }
            }
            changed = nonUniform.size() != before;
        }
        // Parameters only carry the property to their loads; NonUniform belongs on values, not on the parameters.
        parameters.values().forEach(nonUniform::removeAll);
        if (nonUniform.isEmpty()) return source;
        if (!warned) {
            warned = true;
            LOG.log(
                    System.Logger.Level.WARNING,
                    "A shader indexes a sampler array with a non-uniform value; emulating desktop GL per-fragment"
                            + " indexing on Vulkan");
        }

        var output = new ArrayList<Integer>(words.length + nonUniform.size() * 3 + 16);
        for (int i = 0; i < 5; i++) output.add(words[i]);
        boolean prologue = false, decorated = false;
        for (int at = 5; at < words.length; at += length(words, at)) {
            int op = words[at] & 65535, size = length(words, at);
            if (!prologue && op != SpvOpCapability) {
                for (int capability :
                        new int[] {SpvCapabilityShaderNonUniform, SpvCapabilitySampledImageArrayNonUniformIndexing})
                    if (!capabilities.contains(capability)) instruction(output, SpvOpCapability, capability);
                if (!extension) instruction(output, SpvOpExtension, literal(EXTENSION));
                prologue = true;
            }
            if (!decorated && op >= SpvOpTypeVoid && op <= SpvOpTypeForwardPointer) {
                for (int id : nonUniform) instruction(output, SpvOpDecorate, id, SpvDecorationNonUniform);
                decorated = true;
            }
            for (int i = 0; i < size; i++) output.add(words[at + i]);
        }
        if (!decorated) throw new IllegalStateException("SPIR-V has no type section for NonUniform decorations");
        var result =
                ByteBuffer.allocateDirect(Math.multiplyExact(output.size(), 4)).order(ByteOrder.LITTLE_ENDIAN);
        for (int word : output) result.putInt(word);
        return result.flip();
    }

    private static int[] literal(String text) {
        byte[] bytes = (text + "\0").getBytes(StandardCharsets.US_ASCII);
        var padded = ByteBuffer.allocate((bytes.length + 3) / 4 * 4).order(ByteOrder.LITTLE_ENDIAN);
        padded.put(bytes).clear();
        int[] encoded = new int[padded.capacity() / 4];
        padded.asIntBuffer().get(encoded);
        return encoded;
    }

    private static String decode(int[] words, int at) {
        var bytes = ByteBuffer.allocate((length(words, at) - 1) * 4).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 1; i < length(words, at); i++) bytes.putInt(words[at + i]);
        var text = new String(bytes.array(), StandardCharsets.US_ASCII);
        int end = text.indexOf('\0');
        return end < 0 ? text : text.substring(0, end);
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
