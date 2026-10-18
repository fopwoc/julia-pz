package dev.julia.audit.scan;

import dev.julia.audit.model.GraphicsReference;
import dev.julia.audit.model.GraphicsSymbol;
import java.lang.classfile.ClassFile;
import java.lang.classfile.constantpool.FieldRefEntry;
import java.lang.classfile.constantpool.MemberRefEntry;
import java.lang.classfile.constantpool.MethodHandleEntry;
import java.lang.classfile.instruction.ConstantInstruction.LoadConstantInstruction;
import java.lang.classfile.instruction.FieldInstruction;
import java.lang.classfile.instruction.InvokeDynamicInstruction;
import java.lang.classfile.instruction.InvokeInstruction;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Predicate;

public final class GraphicsClassScanner {
    private final Predicate<GraphicsSymbol> accepts;

    public GraphicsClassScanner() {
        this(GraphicsSymbol::isGraphicsBinding);
    }

    public GraphicsClassScanner(Predicate<GraphicsSymbol> accepts) {
        this.accepts = Objects.requireNonNull(accepts);
    }

    public List<GraphicsReference> scan(byte[] bytes) {
        var model = ClassFile.of().parse(bytes);
        String owner = model.thisClass().asInternalName();
        var references = new ArrayList<GraphicsReference>();
        for (var method : model.methods()) {
            var code = method.code();
            if (code.isEmpty()) continue;
            var caller = new Caller(owner, method.methodName().stringValue(), method.methodType().stringValue());
            for (var element : code.orElseThrow()) {
                switch (element) {
                    case InvokeInstruction call -> add(references, call.method(), caller, GraphicsReference.Kind.INSTRUCTION);
                    case FieldInstruction field -> add(references, field.field(), caller, GraphicsReference.Kind.INSTRUCTION);
                    case InvokeDynamicInstruction dynamic -> {
                        var bootstrap = dynamic.invokedynamic().bootstrap();
                        add(references, bootstrap.bootstrapMethod().reference(), caller, GraphicsReference.Kind.METHOD_HANDLE);
                        for (var argument : bootstrap.arguments()) {
                            if (argument instanceof MethodHandleEntry handle) {
                                add(references, handle.reference(), caller, GraphicsReference.Kind.METHOD_HANDLE);
                            }
                        }
                    }
                    case LoadConstantInstruction constant when constant.constantEntry() instanceof MethodHandleEntry handle ->
                        add(references, handle.reference(), caller, GraphicsReference.Kind.METHOD_HANDLE);
                    default -> {}
                }
            }
        }
        return List.copyOf(references);
    }

    private record Caller(String owner, String method, String descriptor) {}

    private void add(List<GraphicsReference> references, MemberRefEntry member, Caller caller, GraphicsReference.Kind kind) {
        var symbolKind = member instanceof FieldRefEntry ? GraphicsSymbol.Kind.FIELD : GraphicsSymbol.Kind.METHOD;
        var symbol = new GraphicsSymbol(symbolKind, member.owner().asInternalName(), member.name().stringValue(),
                member.type().stringValue());
        if (accepts.test(symbol)) {
            references.add(new GraphicsReference(symbol, caller.owner(), caller.method(), caller.descriptor(), kind));
        }
    }
}
