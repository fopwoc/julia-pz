package dev.julia.transform.prepare;

import dev.julia.transform.TestFrames;
import dev.julia.transform.Endpoint;
import dev.julia.runtime.IsolatedTargets;
import dev.julia.runtime.RenderSession;
import java.util.Set;
import java.util.function.IntSupplier;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;

/** Separate JVM integration fixture: typed calls and lambdas target sibling-loader endpoints. */
public final class RuntimeFixtureMain {
    private static final String GLSM = "com/gtnewhorizons/angelica/glsm/GLStateManager";
    private RuntimeFixtureMain() { }

    public static void main(String[] arguments) throws Exception {
        byte[] original;
        try (var input = RuntimeFixtureMain.class.getResourceAsStream("/zombie/RuntimeDispatchFixture.class")) {
            if (input == null) throw new IllegalStateException("Missing synthetic consumer");
            original = input.readAllBytes();
        }
        var prepared = new RuntimePreparation().prepare(original);
        if (!prepared.ready()) throw new IllegalStateException(prepared.blockers().toString());
        var isolated = new ClassLoader(ClassLoader.getPlatformClassLoader()) {
            @Override protected Class<?> findClass(String name) throws ClassNotFoundException {
                if (!name.equals(GLSM.replace('/', '.')) && !name.equals(Endpoint.UNIFORM_BRIDGE.owner().replace('/', '.'))) throw new ClassNotFoundException(name);
                byte[] bytes = endpoint(name.replace('.', '/'));
                return defineClass(name, bytes, 0, bytes.length);
            }
        };
        byte[] transformed = prepared.requireBytecode();
        var consumer = new ClassLoader(RuntimeFixtureMain.class.getClassLoader()) {
            Class<?> define() { return defineClass(null, transformed, 0, transformed.length); }
        }.define();
        var target = new IsolatedTargets(isolated, Set.of(GLSM, Endpoint.UNIFORM_BRIDGE.owner()));
        IntSupplier supplier;
        try (var _ = RenderSession.open(target, TestFrames.idle())) {
            if (!consumer.getMethod("draw").invoke(null).equals(203)) throw new IllegalStateException("Incorrect direct target");
            supplier = (IntSupplier) consumer.getMethod("supplier").invoke(null);
            if (supplier.getAsInt() != 203) throw new IllegalStateException("Incorrect lambda target");
            float[] values = {1, 2};
            consumer.getMethod("upload", float[].class).invoke(null, (Object) values);
            if (values[0] != 17.5f || values[1] != 2) throw new IllegalStateException("Array boundary mismatch");
        }
        try {
            supplier.getAsInt();
            throw new AssertionError("Closed session leaked its target");
        } catch (IllegalStateException expected) {
            if (!expected.getMessage().contains("No render session")) throw expected;
        }
        if (Class.forName(GLSM.replace('/', '.'), false, isolated).getClassLoader() != isolated) throw new AssertionError("Loader isolation lost");
        System.out.println("PASS: direct call, lambda, array overload, isolated endpoint, closed-session rejection");
    }


    private static byte[] endpoint(String name) {
        var writer = new ClassWriter(0);
        writer.visit(Opcodes.V25, Opcodes.ACC_PUBLIC, name, null, "java/lang/Object", null);
        var get = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "glGetError", "()I", null, null);
        get.visitCode();
        get.visitLdcInsn(203);
        get.visitInsn(Opcodes.IRETURN);
        get.visitMaxs(1, 0);
        get.visitEnd();
        var upload = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "glUniform1", "(I[F)V", null, null);
        upload.visitCode();
        upload.visitVarInsn(Opcodes.ALOAD, 1);
        upload.visitInsn(Opcodes.ICONST_0);
        upload.visitLdcInsn(17.5f);
        upload.visitInsn(Opcodes.FASTORE);
        upload.visitInsn(Opcodes.RETURN);
        upload.visitMaxs(3, 2);
        upload.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }
}
