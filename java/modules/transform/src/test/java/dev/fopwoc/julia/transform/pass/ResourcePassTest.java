package dev.fopwoc.julia.transform.pass;

import static org.junit.jupiter.api.Assertions.*;

import dev.fopwoc.julia.transform.Endpoint;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

class ResourcePassTest {
    @Test
    void bridgesOnlyObservedProgramResourceOverloads() {
        var node = new ClassNode();
        var method = new MethodNode();
        node.methods.add(method);
        var index = call("glGetProgramResourceIndex", "(IILjava/lang/CharSequence;)I");
        var name = call("glGetProgramResourceName", "(IIII)Ljava/lang/String;");
        var array = call("glGetProgramResourceiv", "(III[I[I[I)V");
        var nativeBuffer =
                call("glGetProgramResourceiv", "(IIILjava/nio/IntBuffer;Ljava/nio/IntBuffer;Ljava/nio/IntBuffer;)V");
        method.instructions.add(index);
        method.instructions.add(name);
        method.instructions.add(array);
        method.instructions.add(nativeBuffer);
        ResourcePass.adapt(node);
        assertEquals(Endpoint.PROGRAM_RESOURCE_BRIDGE.owner(), index.owner);
        assertEquals(Endpoint.PROGRAM_RESOURCE_BRIDGE.owner(), name.owner);
        assertEquals(Endpoint.PROGRAM_RESOURCE_BRIDGE.owner(), array.owner);
        assertEquals("org/lwjgl/opengl/GL43", nativeBuffer.owner);
    }

    private static MethodInsnNode call(String name, String descriptor) {
        return new MethodInsnNode(Opcodes.INVOKESTATIC, "org/lwjgl/opengl/GL43", name, descriptor, false);
    }
}
