package dev.fopwoc.julia.transform.load;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;

class SamplerResourcePassTest {
    @ParameterizedTest
    @ValueSource(
            strings = {
                "shader.ShaderManager",
                "frame.ContextState",
                "sampler.SamplerBinder",
                "shader.msl.MslCrossCompile",
                "resource.FBOClearTracker",
                "shader.SamplerOverflow"
            })
    void pinnedIntegrationPreservesNativeBindingStrides(String suffix) throws Exception {
        String name = "com.gtnewhorizons.angelica.sdlgpu." + suffix;
        byte[] bytes;
        try (var input = getClass().getClassLoader().getResourceAsStream(name.replace('.', '/') + ".class")) {
            assertNotNull(input, name);
            bytes = input.readAllBytes();
        }
        var adapted = SamplerResourcePass.adapt(name, bytes);
        var node = new ClassNode();
        new ClassReader(adapted).accept(node, 0);
        if (suffix.equals("sampler.SamplerBinder")) {
            int strides = 0;
            for (var method : node.methods)
                if (method.name.equals("bindStageSamplers") || method.name.equals("bindFallbackForStage"))
                    for (var instruction : method.instructions)
                        if (instruction instanceof FieldInsnNode value
                                && value.name.equals("SIZEOF")
                                && value.owner.equals("org/lwjgl/sdl/SDL_GPUTextureSamplerBinding")) strides++;
            assertEquals(3, strides, "Native SDL binding structures still have a 16-byte stride");
        }
    }
}
