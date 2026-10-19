package dev.fopwoc.julia.transform.load;

import dev.fopwoc.julia.transform.Endpoint;
import java.util.Set;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;

/** Select the operation target before GLSM consults its single-binding cache; queries use logical state. */
public final class TextureBindingPass {
    private static final Set<String> OPERATIONS = Set.of(
            "glTexParameteri",
            "glTexParameterf",
            "glTexParameter",
            "glGetTexParameteri",
            "glGetTexParameterf",
            "glGetTexParameter",
            "glTexImage1D",
            "glTexImage2D",
            "glTexImage3D",
            "glTexSubImage1D",
            "glTexSubImage2D",
            "glTexSubImage3D",
            "glTexStorage2D",
            "glTexStorage3D",
            "glGenerateMipmap",
            "glGetTexImage",
            "glGetTexLevelParameteri",
            "glGetTexLevelParameterf",
            "glGetTexLevelParameter",
            "glCopyTexImage1D",
            "glCopyTexImage2D",
            "glCopyTexSubImage1D",
            "glCopyTexSubImage2D",
            "glCopyTexSubImage3D",
            "glCompressedTexImage1D",
            "glCompressedTexImage2D",
            "glCompressedTexImage3D",
            "glCompressedTexSubImage1D",
            "glCompressedTexSubImage2D",
            "glCompressedTexSubImage3D");

    private TextureBindingPass() {}

    public static byte[] adapt(String name, byte[] bytes) {
        if (!name.equals("com.gtnewhorizons.angelica.glsm.GLStateManager")) return bytes;
        var node = new ClassNode();
        new ClassReader(bytes).accept(node, 0);
        int selected = 0, queries = 0, deletes = 0;
        for (var method : node.methods) {
            if (OPERATIONS.contains(method.name)
                    && method.desc.startsWith("(I")
                    && (method.access & Opcodes.ACC_STATIC) != 0) {
                var head = new InsnList();
                head.add(new VarInsnNode(Opcodes.ILOAD, 0));
                head.add(call("select", "(I)V"));
                method.instructions.insert(head);
                selected++;
            }
            if (method.name.equals("glGetInteger")
                    && method.desc.equals("(Lcom/gtnewhorizons/angelica/glsm/GLContextState;I)I")) {
                var head = new InsnList();
                var next = new LabelNode();
                head.add(new VarInsnNode(Opcodes.ILOAD, 1));
                head.add(call("bindingQuery", "(I)Z"));
                head.add(new JumpInsnNode(Opcodes.IFEQ, next));
                head.add(new VarInsnNode(Opcodes.ILOAD, 1));
                head.add(call("query", "(I)I"));
                head.add(new InsnNode(Opcodes.IRETURN));
                head.add(next);
                head.add(new FrameNode(Opcodes.F_SAME, 0, null, 0, null));
                method.instructions.insert(head);
                queries++;
            }
            if (method.name.equals("onDeleteTexture") && method.desc.equals("(I)V")) {
                var head = new InsnList();
                head.add(new VarInsnNode(Opcodes.ILOAD, 0));
                head.add(call("deleted", "(I)V"));
                method.instructions.insert(head);
                deletes++;
            }
        }
        if (selected != 30 || queries != 1 || deletes != 1)
            throw new IllegalStateException(
                    "Texture-binding integration changed: " + selected + "," + queries + "," + deletes);
        var writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        node.accept(writer);
        return writer.toByteArray();
    }

    private static MethodInsnNode call(String name, String descriptor) {
        return new MethodInsnNode(
                Opcodes.INVOKESTATIC, Endpoint.TEXTURE_BINDING_HOOKS.owner(), name, descriptor, false);
    }
}
