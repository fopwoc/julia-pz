package dev.fopwoc.julia.probe.scene;

import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL12.GL_TEXTURE_3D;
import static org.lwjgl.opengl.GL15.*;
import static org.lwjgl.opengl.GL20.*;
import static org.lwjgl.opengl.GL30.*;
import static org.lwjgl.opengl.GL31.GL_COPY_READ_BUFFER;
import static org.lwjgl.opengl.GL33.GL_TIME_ELAPSED;
import static org.lwjgl.opengl.GL41.GL_MAX_VIEWPORTS;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL41;
import org.lwjgl.opengl.GL45;
import org.lwjgl.system.MemoryStack;

/** LWJGL 3 overloads and later-version owners that mods call, each answered without native OpenGL. */
final class OverloadChecks {
    private OverloadChecks() {}

    static void run(Path output) throws IOException {
        Files.deleteIfExists(output.resolve("overloads.txt"));
        viewports();
        programs();
        queries();
        buffers();
        textures();
        Files.writeString(
                output.resolve("overloads.txt"),
                "PASS\nchecks=indexed viewport/scissor/depth,program uniforms,attached shaders,active attributes,"
                        + "query state,named buffer size,vertex attribute queries,boolean array query,"
                        + "float/int array texture uploads,float readback\n");
    }

    private static void viewports() {
        if (glGetInteger(GL_MAX_VIEWPORTS) != 1)
            throw new IllegalStateException("Julia must advertise exactly one viewport");
        GL41.glViewportIndexedf(0, 1, 2, 30, 40);
        GL41.glScissorIndexed(0, 3, 4, 5, 6);
        GL41.glDepthRangeIndexed(0, 0.25, 0.75);
        float[] viewport = new float[4];
        GL41.glGetFloati_v(GL_VIEWPORT, 0, viewport);
        double[] depth = new double[2];
        GL41.glGetDoublei_v(GL_DEPTH_RANGE, 0, depth);
        int[] scissor = new int[4];
        glGetIntegerv(GL_SCISSOR_BOX, scissor);
        if (!Arrays.equals(viewport, new float[] {1, 2, 30, 40})
                || !Arrays.equals(scissor, new int[] {3, 4, 5, 6})
                || Math.abs(depth[0] - 0.25) > 1e-6
                || Math.abs(depth[1] - 0.75) > 1e-6)
            throw new IllegalStateException("Indexed viewport state differs: viewport=" + Arrays.toString(viewport)
                    + " scissor=" + Arrays.toString(scissor) + " depth=" + Arrays.toString(depth));
        try {
            GL41.glViewportIndexedf(1, 0, 0, 1, 1);
            throw new IllegalStateException("A second viewport index was accepted");
        } catch (IllegalArgumentException expected) {
        }
        glDepthRange(0, 1);
    }

    private static void programs() {
        int vertex = glCreateShader(GL_VERTEX_SHADER), fragment = glCreateShader(GL_FRAGMENT_SHADER);
        int program = glCreateProgram();
        try (var stack = MemoryStack.stackPush()) {
            glShaderSource(
                    vertex,
                    "#version 330 core\nin vec4 position;uniform vec4 tint;uniform float scale;"
                            + "void main(){gl_Position=position*scale+tint;}");
            glShaderSource(
                    fragment, "#version 330 core\nuniform int mode;out vec4 color;void main(){color=vec4(mode);}");
            for (int shader : new int[] {vertex, fragment}) {
                glCompileShader(shader);
                if (glGetShaderi(shader, GL_COMPILE_STATUS) == GL_FALSE)
                    throw new IllegalStateException(glGetShaderInfoLog(shader));
                glAttachShader(program, shader);
            }
            glLinkProgram(program);
            if (glGetProgrami(program, GL_LINK_STATUS) == GL_FALSE)
                throw new IllegalStateException(glGetProgramInfoLog(program));
            int[] count = {-1}, shaders = new int[4];
            glGetAttachedShaders(program, count, shaders);
            if (count[0] != 2) throw new IllegalStateException("Attached shader array query found " + count[0]);
            var size = stack.mallocInt(1);
            var type = stack.mallocInt(1);
            String attribute = glGetActiveAttrib(program, 0, 64, size, type);
            if (!attribute.equals("position") || size.get(0) != 1 || type.get(0) != GL_FLOAT_VEC4)
                throw new IllegalStateException(
                        "Active attribute query returned " + attribute + "/" + size.get(0) + "/" + type.get(0));
            glUseProgram(0);
            GL41.glProgramUniform4f(program, glGetUniformLocation(program, "tint"), 1, 2, 3, 4);
            GL41.glProgramUniform1f(program, glGetUniformLocation(program, "scale"), 0.5f);
            GL41.glProgramUniform1i(program, glGetUniformLocation(program, "mode"), 7);
            if (glGetInteger(GL_CURRENT_PROGRAM) != 0)
                throw new IllegalStateException("Program uniform changed the current program");
            float[] tint = new float[4], scale = new float[1];
            int[] mode = new int[1];
            glGetUniformfv(program, glGetUniformLocation(program, "tint"), tint);
            glGetUniformfv(program, glGetUniformLocation(program, "scale"), scale);
            glGetUniformiv(program, glGetUniformLocation(program, "mode"), mode);
            if (!Arrays.equals(tint, new float[] {1, 2, 3, 4}) || scale[0] != 0.5f || mode[0] != 7)
                throw new IllegalStateException("Program uniforms were not stored: tint=" + Arrays.toString(tint)
                        + " scale=" + scale[0] + " mode=" + mode[0]);
        } finally {
            glUseProgram(0);
            glDeleteProgram(program);
            glDeleteShader(vertex);
            glDeleteShader(fragment);
        }
    }

    private static void queries() {
        int query = glGenQueries();
        try {
            glBeginQuery(GL_TIME_ELAPSED, query);
            int current = GL15.glGetQueryi(GL_TIME_ELAPSED, GL_CURRENT_QUERY);
            glEndQuery(GL_TIME_ELAPSED);
            if (current != query
                    || GL15.glGetQueryi(GL_TIME_ELAPSED, GL_CURRENT_QUERY) != 0
                    || GL15.glGetQueryi(GL_TIME_ELAPSED, GL_QUERY_COUNTER_BITS) != 64)
                throw new IllegalStateException("Query state differs: current=" + current);
        } finally {
            glDeleteQueries(query);
        }
    }

    private static void buffers() {
        int buffer = glGenBuffers(), vao = glGenVertexArrays(), copyRead = glGenBuffers();
        try {
            glBindBuffer(GL_COPY_READ_BUFFER, copyRead);
            glBindBuffer(GL_ARRAY_BUFFER, buffer);
            glBufferData(GL_ARRAY_BUFFER, 64, GL_STATIC_DRAW);
            if (GL45.glGetNamedBufferParameteri(buffer, GL_BUFFER_SIZE) != 64
                    || glGetInteger(GL_COPY_READ_BUFFER) != copyRead)
                throw new IllegalStateException("Named buffer query differs or kept its temporary binding");
            glBindVertexArray(vao);
            glVertexAttribPointer(3, 2, GL_FLOAT, true, 16, 8);
            glEnableVertexAttribArray(3);
            if (glGetVertexAttribi(3, GL_VERTEX_ATTRIB_ARRAY_SIZE) != 2
                    || glGetVertexAttribi(3, GL_VERTEX_ATTRIB_ARRAY_STRIDE) != 16
                    || glGetVertexAttribi(3, GL_VERTEX_ATTRIB_ARRAY_TYPE) != GL_FLOAT
                    || glGetVertexAttribi(3, GL_VERTEX_ATTRIB_ARRAY_NORMALIZED) != GL_TRUE
                    || glGetVertexAttribi(3, GL_VERTEX_ATTRIB_ARRAY_ENABLED) != GL_TRUE
                    || glGetVertexAttribi(3, GL_VERTEX_ATTRIB_ARRAY_BUFFER_BINDING) != buffer
                    || glGetVertexAttribPointer(3, GL_VERTEX_ATTRIB_ARRAY_POINTER) != 8)
                throw new IllegalStateException("Vertex attribute queries differ from the recorded pointer");
            try (var stack = MemoryStack.stackPush()) {
                var flag = stack.malloc(1);
                glDepthMask(false);
                glGetBooleanv(GL_DEPTH_WRITEMASK, flag);
                glDepthMask(true);
                if (flag.get(0) != 0) throw new IllegalStateException("Boolean array query missed the depth mask");
            }
        } finally {
            glBindVertexArray(0);
            glBindBuffer(GL_ARRAY_BUFFER, 0);
            glBindBuffer(GL_COPY_READ_BUFFER, 0);
            glDeleteVertexArrays(vao);
            glDeleteBuffers(buffer);
            glDeleteBuffers(copyRead);
        }
    }

    private static void textures() {
        int flat = glGenTextures(), integer = glGenTextures(), volume = glGenTextures();
        try (var stack = MemoryStack.stackPush()) {
            float[] texels = {0.25f, 0.5f, 0.75f, 1, -1, 2, 3.5f, 0};
            glBindTexture(GL_TEXTURE_2D, flat);
            glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA32F, 2, 1, 0, GL_RGBA, GL_FLOAT, texels);
            var read = stack.mallocFloat(8);
            glGetTexImage(GL_TEXTURE_2D, 0, GL_RGBA, GL_FLOAT, read);
            float[] back = new float[8];
            read.get(0, back);
            if (!Arrays.equals(back, texels))
                throw new IllegalStateException("Float array upload read back as " + Arrays.toString(back));
            glBindTexture(GL_TEXTURE_2D, integer);
            glTexImage2D(
                    GL_TEXTURE_2D, 0, GL_RGBA32UI, 1, 1, 0, GL_RGBA_INTEGER, GL_UNSIGNED_INT, new int[] {1, 2, 3, 4});
            if (glGetTexLevelParameteri(GL_TEXTURE_2D, 0, GL_TEXTURE_WIDTH) != 1)
                throw new IllegalStateException("Integer array upload did not allocate its texture");
            glBindTexture(GL_TEXTURE_3D, volume);
            GL12.glTexImage3D(GL_TEXTURE_3D, 0, GL_RGBA32F, 1, 1, 2, 0, GL_RGBA, GL_FLOAT, new float[8]);
            GL12.glTexSubImage3D(GL_TEXTURE_3D, 0, 0, 0, 1, 1, 1, 1, GL_RGBA, GL_FLOAT, stack.floats(1, 2, 3, 4));
            if (glGetTexLevelParameteri(GL_TEXTURE_3D, 0, GL12.GL_TEXTURE_DEPTH) != 2)
                throw new IllegalStateException("Float array volume upload did not allocate both slices");
        } finally {
            glBindTexture(GL_TEXTURE_2D, 0);
            glBindTexture(GL_TEXTURE_3D, 0);
            glDeleteTextures(flat);
            glDeleteTextures(integer);
            glDeleteTextures(volume);
        }
    }
}
