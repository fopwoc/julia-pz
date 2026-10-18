package dev.fopwoc.julia.backend.shader;

import static org.lwjgl.opengl.GL20.*;

import dev.fopwoc.julia.backend.BackendInternals;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;

/** Owns GL shader-object attachments; only complete translated stages enter the upstream shader manager. */
public final class ShaderPrograms implements AutoCloseable {
    private static final System.Logger LOG = System.getLogger(ShaderPrograms.class.getName());

    private static final class Shader {
        final int type;
        String source = "";
        ShaderFrontend.Compiled compiled;
        boolean deleted;
        int attachments;

        Shader(int type) {
            this.type = type;
        }
    }

    private static final class Program {
        final LinkedHashSet<Integer> attachments = new LinkedHashSet<>();
        final ArrayList<Integer> stages = new ArrayList<>();
    }

    private final HashMap<Integer, Shader> shaders = new HashMap<>();
    private final HashMap<Integer, Program> programs = new HashMap<>();
    private final HashSet<Integer> deletedPrograms = new HashSet<>();
    private ShaderFrontend frontend;

    public void created(int id, int type) {
        shaders.put(id, new Shader(type));
    }

    public void programCreated(int id) {
        programs.put(id, new Program());
    }

    public boolean contains(int id) {
        return shaders.containsKey(id);
    }

    public void source(int id, CharSequence source) {
        String text = source.toString();
        shaders.get(id).source = text.endsWith("\0") ? text.substring(0, text.length() - 1) : text;
    }

    public String preprocess(int type, String source) {
        if (frontend == null) frontend = new ShaderFrontend();
        var result = frontend.preprocess(type, source);
        // Preserve the original text on failure so the normal compile call exposes the GLSL error.
        return result.valid() ? result.source() : source;
    }

    public void compile(int id) {
        var shader = shaders.get(id);
        if (frontend == null) frontend = new ShaderFrontend();
        var compiled = frontend.compile(shader.type, shader.source);
        shader.compiled = compiled;
        if (!compiled.valid()) LOG.log(System.Logger.Level.ERROR, "Shader {0} compile failed: {1}", id, compiled.log());
    }

    public void attach(int program, int id) {
        var shader = shaders.get(id);
        if (programs.get(program).attachments.add(id)) shader.attachments++;
    }

    public void detach(int program, int id) {
        if (programs.get(program).attachments.remove(id)) release(id);
    }

    public void delete(int id) {
        var shader = shaders.get(id);
        if (shader == null) return;
        shader.deleted = true;
        if (shader.attachments == 0) destroy(id, shader);
    }

    private void release(int id) {
        var shader = shaders.get(id);
        shader.attachments--;
        if (shader.deleted && shader.attachments == 0) destroy(id, shader);
    }

    private void destroy(int id, Shader shader) {
        shaders.remove(id);
        BackendInternals.shaders().deleteShader(id);
    }

    public void programDeleted(int id) {
        if (programs.containsKey(id)) deletedPrograms.add(id);
        releaseDeletedPrograms();
    }

    public void releaseDeletedPrograms() {
        for (var iterator = deletedPrograms.iterator(); iterator.hasNext(); ) {
            int id = iterator.next();
            if (BackendInternals.shaders().getProgram(id) != null) continue;
            var program = programs.remove(id);
            for (int shader : program.attachments) release(shader);
            iterator.remove();
        }
    }

    public boolean link(int id) {
        var program = programs.get(id);
        var manager = BackendInternals.shaders();
        var compiled = new ArrayList<ShaderFrontend.Compiled>();
        for (int shader : program.attachments) {
            var part = shaders.get(shader).compiled;
            if (part == null || !part.valid()) return fail(id, "Attached shader " + shader + " did not compile");
            compiled.add(part);
        }
        if (frontend == null) return fail(id, "No compiled shader objects attached");
        var linked = frontend.link(compiled);
        if (!linked.valid()) return fail(id, linked.log());
        var stages = new ArrayList<Integer>();
        try {
            for (var stage : linked.stages().entrySet()) {
                int shader = manager.createShader(stage.getKey());
                stages.add(shader);
                manager.shaderSource(shader, stage.getValue());
                manager.compileShader(shader);
                if (!manager.isCompiled(shader))
                    return fail(id, "Backend stage translation: " + manager.getShaderInfoLog(shader));
            }
            for (int old : program.stages) BackendInternals.detachShader(id, old);
            for (int shader : stages) manager.attachShader(id, shader);
            program.stages.clear();
            program.stages.addAll(stages);
            stages.clear();
            // Normal program release owns these internal shader attachments from here on.
            for (int shader : program.stages) manager.deleteShader(shader);
            return true;
        } finally {
            for (int shader : stages) manager.deleteShader(shader);
        }
    }

    private static boolean fail(int id, String log) {
        var object = BackendInternals.shaders().getProgram(id);
        object.linked = false;
        object.infoLog = log;
        LOG.log(System.Logger.Level.ERROR, "Program {0} link failed: {1}", id, log);
        return false;
    }

    public int parameter(int id, int name) {
        var shader = shaders.get(id);
        return switch (name) {
            case GL_SHADER_TYPE -> shader.type;
            case GL_COMPILE_STATUS -> shader.compiled != null && shader.compiled.valid() ? 1 : 0;
            case GL_DELETE_STATUS -> shader.deleted ? 1 : 0;
            case GL_SHADER_SOURCE_LENGTH -> shader.source.getBytes(StandardCharsets.UTF_8).length + 1;
            case GL_INFO_LOG_LENGTH -> log(id).isEmpty() ? 0 : log(id).getBytes(StandardCharsets.UTF_8).length + 1;
            default -> 0;
        };
    }

    public String source(int id) {
        return shaders.get(id).source;
    }

    public String log(int id) {
        var compiled = shaders.get(id).compiled;
        return compiled == null ? "" : compiled.log();
    }

    public int attached(int id) {
        return programs.get(id).attachments.size();
    }

    public void attached(int id, IntBuffer count, IntBuffer output) {
        int written = 0;
        for (int shader : programs.get(id).attachments) {
            if (written == output.remaining()) break;
            output.put(output.position() + written++, shader);
        }
        if (count != null && count.hasRemaining()) count.put(count.position(), written);
    }

    public static String truncate(String text, int capacity) {
        return capacity <= 0 ? "" : text.substring(0, Math.min(text.length(), capacity - 1));
    }

    public static void write(String text, IntBuffer length, ByteBuffer output) {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        int size = output == null ? 0 : Math.min(bytes.length, Math.max(0, output.remaining() - 1));
        if (output != null && output.hasRemaining()) {
            int start = output.position();
            for (int i = 0; i < size; i++) output.put(start + i, bytes[i]);
            output.put(start + size, (byte) 0);
        }
        if (length != null && length.hasRemaining()) length.put(length.position(), size);
    }

    @Override
    public void close() {
        shaders.clear();
        programs.clear();
        deletedPrograms.clear();
        if (frontend != null) {
            frontend.close();
            frontend = null;
        }
    }
}
