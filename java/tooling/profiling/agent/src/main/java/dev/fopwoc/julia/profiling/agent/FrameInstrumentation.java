package dev.fopwoc.julia.profiling.agent;

import static java.lang.constant.ConstantDescs.CD_boolean;
import static java.lang.constant.ConstantDescs.CD_void;

import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassHierarchyResolver;
import java.lang.classfile.ClassTransform;
import java.lang.classfile.CodeBuilder;
import java.lang.classfile.CodeElement;
import java.lang.classfile.CodeTransform;
import java.lang.classfile.Opcode;
import java.lang.classfile.instruction.InvokeInstruction;
import java.lang.classfile.instruction.ReturnInstruction;
import java.lang.constant.ClassDesc;
import java.lang.constant.MethodTypeDesc;

/** Observes completed PZ render steps without touching GL calls or the renderer's own frame hooks. */
public final class FrameInstrumentation {
    public static final String TARGET = "zombie/core/opengl/RenderThread";
    private static final ClassDesc HOOKS = ClassDesc.of("dev.fopwoc.julia.profiling.agent.ProfilingHooks");

    private FrameInstrumentation() {}

    public static byte[] instrument(byte[] bytes) {
        return instrument(bytes, FrameInstrumentation.class.getClassLoader());
    }

    public static byte[] instrument(byte[] bytes, ClassLoader loader) {
        var api = ClassFile.of(
                ClassFile.ClassHierarchyResolverOption.of(ClassHierarchyResolver.ofResourceParsing(loader)));
        var model = api.parse(bytes);
        if (!model.thisClass().asInternalName().equals(TARGET))
            throw new IllegalArgumentException("Unexpected frame owner");
        var methods = model.methods().stream()
                .filter(method -> method.methodName().equalsString("lockStepRenderStep")
                        && method.methodType().equalsString("()Z")
                        && (method.flags().flagsMask() & ClassFile.ACC_STATIC) != 0)
                .toList();
        if (methods.size() != 1 || methods.getFirst().code().isEmpty())
            throw new IllegalArgumentException("Expected one static RenderThread.lockStepRenderStep()Z body");
        for (var element : methods.getFirst().code().orElseThrow())
            if (element instanceof InvokeInstruction call
                    && call.owner().asSymbol().equals(HOOKS))
                throw new IllegalArgumentException("Render step is already instrumented");
        return api.transformClass(
                model, ClassTransform.transformingMethodBodies(methods::contains, new CodeTransform() {
                    @Override
                    public void atStart(CodeBuilder builder) {
                        builder.invokestatic(HOOKS, "begin", MethodTypeDesc.of(CD_void));
                    }

                    @Override
                    public void accept(CodeBuilder builder, CodeElement element) {
                        if (element instanceof ReturnInstruction instruction && instruction.opcode() == Opcode.IRETURN)
                            builder.dup().invokestatic(HOOKS, "complete", MethodTypeDesc.of(CD_void, CD_boolean));
                        builder.with(element);
                    }
                }));
    }
}
