package dev.julia.transform.pass;

import static org.junit.jupiter.api.Assertions.*;

import dev.julia.runtime.RenderSession;
import dev.julia.transform.Endpoint;
import dev.julia.transform.TestFrames;
import dev.julia.transform.prepare.RuntimePreparation;
import java.lang.invoke.MethodHandles;
import java.util.Objects;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.MethodNode;

class CapabilityPassTest {
    @Test
    void queriesBackendSnapshotAndVerifiesRemappedLocalsAcrossBranches() throws Exception {
        byte[] original;
        try (var input = getClass().getResourceAsStream("/zombie/CapabilityFixture.class")) {
            original = Objects.requireNonNull(input).readAllBytes();
        }
        var result = new RuntimePreparation().prepare(original);
        assertTrue(result.ready(), result.blockers().toString());
        byte[] bytes = result.requireBytecode();
        var type = new ClassLoader(getClass().getClassLoader()) {
            Class<?> define() {
                return defineClass(null, bytes, 0, bytes.length);
            }
        }.define();
        var session = RenderSession.open(
                (owner, name, signature) -> {
                    assertEquals(Endpoint.CAPABILITY_BRIDGE.owner(), owner);
                    assertEquals("current", name);
                    return MethodHandles.constant(
                            Set.class, Set.of("OpenGL33", "GL_ARB_framebuffer_object", "GL_ARB_texture_compression"));
                },
                TestFrames.idle());
        try {
            assertEquals(11, type.getMethod("flags", boolean.class).invoke(null, true));
            assertEquals(10, type.getMethod("flags", boolean.class).invoke(null, false));
            assertEquals(true, type.getMethod("displayFlag").invoke(null));
        } finally {
            session.close();
        }
    }

    @Test
    void rejectsNativePointersAndLeavesOriginalUntouched() {
        var node = new ClassNode();
        node.version = Opcodes.V25;
        node.access = Opcodes.ACC_PUBLIC;
        node.name = "fixture/Capabilities";
        node.superName = "java/lang/Object";
        var method = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "test", "()V", null, null);
        node.methods.add(method);
        var pointer = new FieldInsnNode(Opcodes.GETFIELD, "org/lwjgl/opengl/GLCapabilities", "glGenTextures", "J");
        method.instructions.add(pointer);
        assertThrows(IllegalArgumentException.class, () -> CapabilityPass.adapt(node));
        assertEquals("org/lwjgl/opengl/GLCapabilities", pointer.owner);
        assertEquals(1, method.instructions.size());
    }

    @Test
    void refusesCapabilityObjectsEscapingThroughDeclaredFields() {
        var node = new ClassNode();
        node.version = Opcodes.V25;
        node.access = Opcodes.ACC_PUBLIC;
        node.name = "fixture/Capabilities";
        node.superName = "java/lang/Object";
        node.fields.add(new FieldNode(Opcodes.ACC_PRIVATE, "cached", "Lorg/lwjgl/opengl/GLCapabilities;", null, null));
        assertThrows(IllegalArgumentException.class, () -> CapabilityPass.adapt(node));
    }

    @Test
    void refusesWritesAndUnknownBooleanProperties() {
        for (String name : new String[] {"OpenGL33", "forwardCompatible"}) {
            var node = new ClassNode();
            node.version = Opcodes.V25;
            node.access = Opcodes.ACC_PUBLIC;
            node.name = "fixture/Capabilities";
            node.superName = "java/lang/Object";
            var method = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "test", "()V", null, null);
            node.methods.add(method);
            method.instructions.add(new FieldInsnNode(
                    name.equals("OpenGL33") ? Opcodes.PUTFIELD : Opcodes.GETFIELD,
                    "org/lwjgl/opengl/GLCapabilities",
                    name,
                    "Z"));
            assertThrows(IllegalArgumentException.class, () -> CapabilityPass.adapt(node));
        }
    }
}
