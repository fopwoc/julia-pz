package dev.fopwoc.julia.transform.pass;

import static org.junit.jupiter.api.Assertions.*;

import dev.fopwoc.julia.transform.Endpoint;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ConstantDynamic;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

class UniformOverloadPassTest {
    private static final String GLSM = "com/gtnewhorizons/angelica/glsm/GLStateManager";

    @Test
    void substitutesOnlyExactMissingSignatures() {
        var node = new ClassNode();
        var method = new MethodNode();
        node.methods.add(method);
        var array = call(GLSM, "glUniform1", "(I[F)V");
        var matrix = call(GLSM, "glUniformMatrix4", "(IZ[F)V");
        var reflection =
                call(GLSM, "glGetActiveUniform", "(IIILjava/nio/IntBuffer;Ljava/nio/IntBuffer;)Ljava/lang/String;");
        var buffer = call(GLSM, "glUniform1", "(ILjava/nio/FloatBuffer;)V");
        var pointer = call(GLSM, "glTexImage3D", "(IIIIIIIIIJ)V");
        var nativeCall = call("org/lwjgl/opengl/GL20", "glUniform1", "(I[F)V");
        for (var instruction : new MethodInsnNode[] {array, matrix, reflection, buffer, pointer, nativeCall}) {
            method.instructions.add(instruction);
        }
        UniformOverloadPass.adapt(node);
        for (var instruction : new MethodInsnNode[] {array, matrix, reflection})
            assertEquals(Endpoint.UNIFORM_BRIDGE.owner(), instruction.owner);
        assertEquals(GLSM, buffer.owner);
        assertEquals(GLSM, pointer.owner);
        assertEquals("org/lwjgl/opengl/GL20", nativeCall.owner);
    }

    @Test
    void redirectsHandlesInLambdasAndNestedDynamicConstants() {
        var handle = new Handle(Opcodes.H_INVOKESTATIC, GLSM, "glUniform1", "(I[F)V", false);
        var bootstrap = new Handle(Opcodes.H_INVOKESTATIC, "fixture/Bootstrap", "link", "()V", false);
        var constant = new LdcInsnNode(new ConstantDynamic(
                "outer",
                "Ljava/lang/Object;",
                bootstrap,
                new ConstantDynamic("inner", "Ljava/lang/Object;", bootstrap, handle)));
        var lambda = new InvokeDynamicInsnNode("accept", "()Ljava/util/function/BiConsumer;", bootstrap, handle);
        var node = new ClassNode();
        var method = new MethodNode();
        node.methods.add(method);
        method.instructions.add(constant);
        method.instructions.add(lambda);
        UniformOverloadPass.adapt(node);
        var outer = (ConstantDynamic) constant.cst;
        var inner = (ConstantDynamic) outer.getBootstrapMethodArgument(0);
        assertEquals(Endpoint.UNIFORM_BRIDGE.owner(), ((Handle) inner.getBootstrapMethodArgument(0)).getOwner());
        assertEquals(Endpoint.UNIFORM_BRIDGE.owner(), ((Handle) lambda.bsmArgs[0]).getOwner());
        assertEquals(bootstrap, lambda.bsm);
        assertEquals("(I[F)V", ((Handle) lambda.bsmArgs[0]).getDesc());
    }

    private static MethodInsnNode call(String owner, String name, String descriptor) {
        return new MethodInsnNode(Opcodes.INVOKESTATIC, owner, name, descriptor, false);
    }
}
