package dev.fopwoc.julia.backend.layer;

import static org.lwjgl.opengl.GL11.GL_INVALID_OPERATION;
import static org.lwjgl.opengl.GL12.*;
import static org.lwjgl.opengl.GL20.GL_ACTIVE_UNIFORMS;
import static org.lwjgl.opengl.GL20.GL_ACTIVE_UNIFORM_MAX_LENGTH;
import static org.lwjgl.opengl.GL20.GL_ATTACHED_SHADERS;
import static org.lwjgl.opengl.GL20.GL_LINK_STATUS;
import static org.lwjgl.opengl.GL30.*;

import dev.fopwoc.julia.backend.Upstream;
import dev.fopwoc.julia.backend.shader.MatrixUniformUploads;
import dev.fopwoc.julia.backend.shader.ProgramLinkState;
import dev.fopwoc.julia.backend.shader.SamplerUniforms;
import dev.fopwoc.julia.backend.shader.ShaderPrograms;
import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

/** GLSL program objects, public uniform names, and validated uniform uploads over SDL shader translation. */
public abstract class ShaderLayer extends TextureLayer {
    private static final System.Logger LOG = System.getLogger(ShaderLayer.class.getName());

    @Override
    public int getUniformLocation(int program, CharSequence name) {
        var programs = subsystems().shaderPrograms();
        String key = name.toString();
        int location = programs.cachedLocation(program, key);
        if (location != ShaderPrograms.UNRESOLVED) return location;
        String backendName = programs.backendUniformName(program, key);
        var object = Upstream.shaders().getProgram(program);
        if (object != null && object.allSamplerNames.contains(backendName + "[0]")) backendName += "[0]";
        location = super.getUniformLocation(program, backendName);
        // Unlinked programs report GL errors on lookup, so only stable answers are remembered.
        if (object != null && object.linked) programs.cacheLocation(program, key, location);
        return location;
    }

    @Override
    public int getUniformLocation(int program, ByteBuffer name) {
        return getUniformLocation(program, MemoryUtil.memASCII(name));
    }

    @Override
    public String getActiveUniform(int program, int index, int capacity, IntBuffer sizeType) {
        String name = publicUniformName(program, index, sizeType);
        return capacity > 0 && name.length() > capacity ? name.substring(0, capacity) : name;
    }

    private String publicUniformName(int program, int index, IntBuffer sizeType) {
        String name = super.getActiveUniform(program, index, 0, sizeType);
        if (sizeType != null && sizeType.remaining() >= 2) {
            int at = sizeType.position() + 1;
            sizeType.put(
                    at,
                    subsystems()
                            .bufferTextures()
                            .uniformType(Upstream.shaders().getProgram(program), name, sizeType.get(at)));
        }
        return subsystems().shaderPrograms().publicUniformName(program, name);
    }

    @Override
    public void getActiveUniform(
            int program, int index, IntBuffer length, IntBuffer size, IntBuffer type, ByteBuffer name) {
        try (var stack = MemoryStack.stackPush()) {
            var sizeType = stack.mallocInt(2);
            String publicName = publicUniformName(program, index, sizeType);
            if (size != null && size.hasRemaining()) size.put(size.position(), sizeType.get(0));
            if (type != null && type.hasRemaining()) type.put(type.position(), sizeType.get(1));
            ShaderPrograms.write(publicName, length, name);
        }
    }

    @Override
    public int createShader(int type) {
        int id = super.createShader(type);
        subsystems().shaderPrograms().created(id, type);
        return id;
    }

    @Override
    public int createProgram() {
        int id = super.createProgram();
        subsystems().shaderPrograms().programCreated(id);
        return id;
    }

    @Override
    public void shaderSource(int shader, CharSequence source) {
        subsystems().shaderPrograms().source(shader, source);
    }

    @Override
    public void compileShader(int shader) {
        subsystems().shaderPrograms().compile(shader);
    }

    @Override
    public void deleteShader(int shader) {
        subsystems().shaderPrograms().delete(shader);
    }

    @Override
    public void attachShader(int program, int shader) {
        subsystems().shaderPrograms().attach(program, shader);
    }

    @Override
    public void detachShader(int program, int shader) {
        subsystems().shaderPrograms().detach(program, shader);
    }

    @Override
    public void linkProgram(int program) {
        if (subsystems().shaderPrograms().link(program)) {
            ProgramLinkState.reset(Upstream.shaders().getProgram(program));
            super.linkProgram(program);
            if (super.getProgrami(program, GL_LINK_STATUS) == 0)
                throw new UnsupportedOperationException("SDL shader program translation failed for program " + program
                        + ": " + super.getProgramInfoLog(program));
            ProgramLinkState.linked(Upstream.shaders().getProgram(program), Upstream.context());
            subsystems().shaderPrograms().initializeUniforms(program);
        }
    }

    @Override
    public void uniform1iv(int location, IntBuffer values) {
        if (!SamplerUniforms.upload(location, values)) super.uniform1iv(location, values);
    }

    @Override
    public void deleteProgram(int program) {
        super.deleteProgram(program);
        subsystems().shaderPrograms().programDeleted(program);
    }

    @Override
    public void useProgram(int program) {
        super.useProgram(program);
        subsystems().shaderPrograms().releaseDeletedPrograms();
    }

    @Override
    public int getShaderi(int shader, int parameter) {
        return subsystems().shaderPrograms().contains(shader)
                ? subsystems().shaderPrograms().parameter(shader, parameter)
                : super.getShaderi(shader, parameter);
    }

    @Override
    public int getProgrami(int program, int parameter) {
        if (parameter == GL_ACTIVE_UNIFORM_MAX_LENGTH
                && subsystems().shaderPrograms().renamedTexture(program)) {
            int longest = 0;
            for (int i = 0; i < super.getProgrami(program, GL_ACTIVE_UNIFORMS); i++)
                longest = Math.max(longest, publicUniformName(program, i, null).length() + 1);
            return longest;
        }
        return parameter == GL_ATTACHED_SHADERS
                ? subsystems().shaderPrograms().attached(program)
                : super.getProgrami(program, parameter);
    }

    @Override
    public void getAttachedShaders(int program, IntBuffer count, IntBuffer shaders) {
        subsystems().shaderPrograms().attached(program, count, shaders);
    }

    @Override
    public String getShaderInfoLog(int shader, int maxLength) {
        return ShaderPrograms.truncate(subsystems().shaderPrograms().log(shader), maxLength);
    }

    @Override
    public void getShaderInfoLog(int shader, IntBuffer length, ByteBuffer output) {
        ShaderPrograms.write(subsystems().shaderPrograms().log(shader), length, output);
    }

    @Override
    public String getShaderSource(int shader, int maxLength) {
        return ShaderPrograms.truncate(subsystems().shaderPrograms().source(shader), maxLength);
    }

    @Override
    public void getShaderSource(int shader, IntBuffer length, ByteBuffer output) {
        ShaderPrograms.write(subsystems().shaderPrograms().source(shader), length, output);
    }

    @Override
    public void uniformMatrix2(int location, boolean transpose, FloatBuffer value) {
        var checked = matrixUpload(location, value, 2);
        if (checked != null) super.uniformMatrix2(location, transpose, checked);
    }

    @Override
    public void uniformMatrix3(int location, boolean transpose, FloatBuffer value) {
        var checked = matrixUpload(location, value, 3);
        if (checked != null) super.uniformMatrix3(location, transpose, checked);
    }

    @Override
    public void uniformMatrix4(int location, boolean transpose, FloatBuffer value) {
        var checked = matrixUpload(location, value, 4);
        if (checked != null) super.uniformMatrix4(location, transpose, checked);
    }

    private FloatBuffer matrixUpload(int location, FloatBuffer value, int size) {
        if (location == -1) return null;
        var context = Upstream.context();
        try {
            return MatrixUniformUploads.prepare(context.boundProgramObj, location, size, value);
        } catch (IllegalArgumentException invalid) {
            recordError(GL_INVALID_OPERATION);
            LOG.log(
                    System.Logger.Level.DEBUG,
                    "Rejected matrix uniform upload: program={0}, uniform={1}, location={2}, size={3}, count={4}: {5}",
                    context.boundProgram,
                    context.boundProgramObj == null
                            ? "<none>"
                            : context.boundProgramObj.locationToName.getOrDefault(location, "<unknown>"),
                    location,
                    size,
                    value.remaining() / (size * size),
                    invalid.getMessage());
            return null;
        }
    }
}
