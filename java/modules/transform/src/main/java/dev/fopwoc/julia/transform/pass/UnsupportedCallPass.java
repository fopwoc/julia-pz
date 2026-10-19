package dev.fopwoc.julia.transform.pass;

import dev.fopwoc.julia.audit.model.GraphicsSymbol;
import dev.fopwoc.julia.transform.Endpoint;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.objectweb.asm.ConstantDynamic;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

/** Replaces unsupported operations with stack-compatible throwing helpers, leaving unused branches usable. */
public final class UnsupportedCallPass implements Opcodes {
    @FunctionalInterface
    public interface Classifier {
        String classify(GraphicsSymbol symbol) throws IOException;
    }

    private UnsupportedCallPass() {}

    public static List<String> adapt(ClassNode node, Classifier classifier) throws IOException {
        var helpers = new ArrayList<MethodNode>();
        var diagnostics = new ArrayList<String>();
        for (var method : node.methods) {
            String source = node.name + "." + method.name + method.desc;
            for (var instruction : method.instructions.toArray()) {
                if (instruction instanceof MethodInsnNode call) {
                    String reason = classifier.classify(
                            new GraphicsSymbol(GraphicsSymbol.Kind.METHOD, call.owner, call.name, call.desc));
                    if (reason == null) continue;
                    if (call.name.equals("<init>"))
                        throw new IllegalArgumentException(
                                "Unsupported graphics constructor: " + source + ": " + reason);
                    String descriptor = call.getOpcode() == INVOKESTATIC ? call.desc : receiver(call.owner, call.desc);
                    var handle = helper(node, helpers, descriptor, source + ": " + reason, diagnostics);
                    method.instructions.set(
                            call,
                            new MethodInsnNode(
                                    INVOKESTATIC,
                                    node.name,
                                    handle.getName(),
                                    descriptor,
                                    (node.access & ACC_INTERFACE) != 0));
                } else if (instruction instanceof FieldInsnNode field) {
                    String reason = classifier.classify(
                            new GraphicsSymbol(GraphicsSymbol.Kind.FIELD, field.owner, field.name, field.desc));
                    if (reason == null) continue;
                    String descriptor = switch (field.getOpcode()) {
                        case GETSTATIC -> "()" + field.desc;
                        case PUTSTATIC -> "(" + field.desc + ")V";
                        case GETFIELD -> "(L" + field.owner + ";)" + field.desc;
                        case PUTFIELD -> "(L" + field.owner + ";" + field.desc + ")V";
                        default -> throw new IllegalArgumentException("Unknown field operation");
                    };
                    var handle = helper(node, helpers, descriptor, source + ": " + reason, diagnostics);
                    method.instructions.set(
                            field,
                            new MethodInsnNode(
                                    INVOKESTATIC,
                                    node.name,
                                    handle.getName(),
                                    descriptor,
                                    (node.access & ACC_INTERFACE) != 0));
                } else if (instruction instanceof LdcInsnNode constant) {
                    constant.cst = constant(node, helpers, constant.cst, source, classifier, diagnostics);
                } else if (instruction instanceof InvokeDynamicInsnNode dynamic) {
                    dynamic.bsm = (Handle) constant(node, helpers, dynamic.bsm, source, classifier, diagnostics);
                    for (int i = 0; i < dynamic.bsmArgs.length; i++)
                        dynamic.bsmArgs[i] =
                                constant(node, helpers, dynamic.bsmArgs[i], source, classifier, diagnostics);
                }
            }
        }
        node.methods.addAll(helpers);
        return List.copyOf(diagnostics);
    }

    private static Object constant(
            ClassNode node,
            List<MethodNode> helpers,
            Object value,
            String source,
            Classifier classifier,
            List<String> diagnostics)
            throws IOException {
        if (value instanceof Handle handle) {
            boolean field = handle.getTag() <= H_PUTSTATIC;
            String reason = classifier.classify(new GraphicsSymbol(
                    field ? GraphicsSymbol.Kind.FIELD : GraphicsSymbol.Kind.METHOD,
                    handle.getOwner(),
                    handle.getName(),
                    handle.getDesc()));
            if (reason == null) return value;
            String descriptor = switch (handle.getTag()) {
                case H_INVOKESTATIC -> handle.getDesc();
                case H_INVOKEVIRTUAL, H_INVOKEINTERFACE, H_INVOKESPECIAL ->
                    receiver(handle.getOwner(), handle.getDesc());
                case H_GETSTATIC -> "()" + handle.getDesc();
                case H_PUTSTATIC -> "(" + handle.getDesc() + ")V";
                case H_GETFIELD -> "(L" + handle.getOwner() + ";)" + handle.getDesc();
                case H_PUTFIELD -> "(L" + handle.getOwner() + ";" + handle.getDesc() + ")V";
                case H_NEWINVOKESPECIAL ->
                    Type.getMethodDescriptor(
                            Type.getObjectType(handle.getOwner()), Type.getArgumentTypes(handle.getDesc()));
                default -> throw new IllegalArgumentException("Unknown graphics handle");
            };
            return helper(node, helpers, descriptor, source + ": " + reason, diagnostics);
        }
        if (value instanceof ConstantDynamic dynamic) {
            var bootstrap =
                    (Handle) constant(node, helpers, dynamic.getBootstrapMethod(), source, classifier, diagnostics);
            Object[] arguments = new Object[dynamic.getBootstrapMethodArgumentCount()];
            for (int i = 0; i < arguments.length; i++)
                arguments[i] =
                        constant(node, helpers, dynamic.getBootstrapMethodArgument(i), source, classifier, diagnostics);
            return new ConstantDynamic(dynamic.getName(), dynamic.getDescriptor(), bootstrap, arguments);
        }
        return value;
    }

    private static String receiver(String owner, String descriptor) {
        return "(L" + owner + ";" + descriptor.substring(1);
    }

    private static Handle helper(
            ClassNode node, List<MethodNode> helpers, String descriptor, String message, List<String> diagnostics) {
        String name = "julia$unsupported$" + helpers.size();
        if (node.methods.stream().anyMatch(method -> method.name.equals(name)))
            throw new IllegalArgumentException("Reserved Julia helper name: " + name);
        var helper = new MethodNode(ACC_PRIVATE | ACC_STATIC | ACC_SYNTHETIC, name, descriptor, null, null);
        helper.instructions.add(new LdcInsnNode(message));
        helper.instructions.add(new MethodInsnNode(
                INVOKESTATIC,
                Endpoint.UNSUPPORTED_GRAPHICS.owner(),
                "failure",
                "(Ljava/lang/String;)Ljava/lang/UnsupportedOperationException;",
                false));
        helper.instructions.add(new InsnNode(ATHROW));
        helpers.add(helper);
        diagnostics.add(message);
        return new Handle(H_INVOKESTATIC, node.name, name, descriptor, (node.access & ACC_INTERFACE) != 0);
    }
}
