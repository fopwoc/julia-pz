package dev.fopwoc.julia.transform.pass;

import static org.junit.jupiter.api.Assertions.*;

import dev.fopwoc.julia.runtime.CapabilitySnapshot;
import dev.fopwoc.julia.transform.prepare.RuntimePreparation;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

class CapabilityPassTest {
    private static final String CAPS = "Lorg/lwjgl/opengl/GLCapabilities;";

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
        var snapshot = CapabilitySnapshot.publish(
                Set.of("OpenGL33", "GL_ARB_framebuffer_object", "GL_ARB_texture_compression"));
        try (snapshot) {
            assertEquals(11, type.getMethod("flags", boolean.class).invoke(null, true));
            assertEquals(10, type.getMethod("flags", boolean.class).invoke(null, false));
            assertEquals(true, type.getMethod("displayFlag").invoke(null));
            var flags = CompletableFuture.supplyAsync(() -> {
                try {
                    return type.getMethod("flags", boolean.class).invoke(null, true);
                } catch (ReflectiveOperationException failure) {
                    throw new IllegalStateException(failure);
                }
            });
            assertEquals(11, flags.get());
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
    void remapsCapabilityObjectsStoredWithinThePackage() {
        var node = fixture();
        node.fields.add(new FieldNode(Opcodes.ACC_PRIVATE, "cached", CAPS, null, null));
        var method = new MethodNode(Opcodes.ACC_STATIC, "keep", "(" + CAPS + ")V", null, null);
        method.instructions.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC, "fixture/Profile", "of", "(" + CAPS + ")Lfixture/Profile;", false));
        node.methods.add(method);
        var adapted = CapabilityPass.adapt(node);
        assertEquals("Ljava/util/Set;", adapted.fields.getFirst().desc);
        var remapped = adapted.methods.getFirst();
        assertEquals("(Ljava/util/Set;)V", remapped.desc);
        assertEquals("(Ljava/util/Set;)Lfixture/Profile;", ((MethodInsnNode) remapped.instructions.getFirst()).desc);
    }

    @Test
    void refusesCapabilityObjectsCrossingIntoOtherPackages() {
        var node = fixture();
        var method = new MethodNode(Opcodes.ACC_STATIC, "install", "()V", null, null);
        method.instructions.add(
                new MethodInsnNode(Opcodes.INVOKESTATIC, "org/lwjgl/opengl/GL", "setCapabilities", "(" + CAPS + ")V"));
        node.methods.add(method);
        assertThrows(IllegalArgumentException.class, () -> CapabilityPass.adapt(node));
    }

    private static ClassNode fixture() {
        var node = new ClassNode();
        node.version = Opcodes.V25;
        node.access = Opcodes.ACC_PUBLIC;
        node.name = "fixture/Capabilities";
        node.superName = "java/lang/Object";
        return node;
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
