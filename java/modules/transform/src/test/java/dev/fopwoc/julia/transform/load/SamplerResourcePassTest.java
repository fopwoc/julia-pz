package dev.fopwoc.julia.transform.load;

import static org.junit.jupiter.api.Assertions.*;

import dev.fopwoc.julia.transform.Endpoint;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.IntInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;

class SamplerResourcePassTest {
    @ParameterizedTest
    @ValueSource(
            strings = {
                "shader.ShaderManager",
                "frame.ContextState",
                "sampler.SamplerBinder",
                "shader.msl.MslCrossCompile",
                "resource.FBOClearTracker",
                "shader.SamplerOverflow",
                "compute.VertexWriteReplayer",
                "compute.VertexWriteReplayer$Replay",
                "glsm.GLStateManager"
            })
    void pinnedIntegrationPreservesNativeBindingStrides(String suffix) throws Exception {
        String name =
                (suffix.startsWith("glsm.") ? "com.gtnewhorizons.angelica." : "com.gtnewhorizons.angelica.sdlgpu.")
                        + suffix;
        byte[] bytes;
        try (var input = getClass().getClassLoader().getResourceAsStream(name.replace('.', '/') + ".class")) {
            assertNotNull(input, name);
            bytes = input.readAllBytes();
        }
        var adapted = new SamplerResourcePass().adapt(name, bytes);
        var node = new ClassNode();
        new ClassReader(adapted).accept(node, 0);
        if (suffix.equals("shader.ShaderManager")) {
            int hooks = 0;
            for (var method : node.methods)
                for (var instruction : method.instructions)
                    if (instruction instanceof MethodInsnNode call) {
                        assertFalse(
                                call.owner.equals("org/lwjgl/sdl/SDLGPU") && call.name.equals("SDL_CreateGPUShader"));
                        if (call.owner.equals(Endpoint.SAMPLER_SHADER_BRIDGE.owner())
                                && call.name.equals("createShader")) {
                            assertEquals("(JLorg/lwjgl/sdl/SDL_GPUShaderCreateInfo;)J", call.desc);
                            hooks++;
                        }
                    }
            assertEquals(1, hooks);
        }
        if (suffix.equals("compute.VertexWriteReplayer$Replay")) {
            int upperBounds = 0;
            for (var method : node.methods)
                if (method.name.equals("resolveSamplers"))
                    for (var instruction : method.instructions)
                        if (instruction instanceof IntInsnNode value && value.operand == 255) {
                            assertEquals(Opcodes.SIPUSH, value.getOpcode(), "Unit 255 must not become signed byte -1");
                            upperBounds++;
                        }
            assertEquals(1, upperBounds);
        }
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
