package dev.fopwoc.julia.probe.scene;

import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL15.*;
import static org.lwjgl.opengl.GL20.*;
import static org.lwjgl.opengl.GL30.*;
import static org.lwjgl.opengl.GL31.GL_UNIFORM_BUFFER;
import static org.lwjgl.opengl.GL43.*;

import com.gtnewhorizons.angelica.glsm.backend.BackendManager;
import com.gtnewhorizons.angelica.sdlgpu.geometry.GeometryAssembly;
import com.gtnewhorizons.angelica.sdlgpu.geometry.GeometryCompute;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import org.lwjgl.system.MemoryUtil;

/** Explicitly requested, windowless GPU oracle for emission, routing, strip assembly and overflow. */
public final class GeometryScene {
    private GeometryScene() {}

    private static final String SOURCE = """
            #version 460 core
            layout(triangles,invocations=6) in;
            layout(triangle_strip,max_vertices=6) out;
            in vec2 incoming[];
            out vec2 outgoing;
            flat out int face;
            void main() {
                for (int part=0;part<2;part++) {
                    for (int i=0;i<3;i++) {
                        gl_Position=gl_in[i].gl_Position+vec4(0,float(part)*0.1,float(gl_InvocationID)*0.1,0);
                        outgoing=incoming[i]+vec2(float(gl_InvocationID)*0.01,float(part)*0.01);
                        face=gl_InvocationID;
                        gl_ViewportIndex=gl_InvocationID;
                        gl_Layer=part;
                        gl_PrimitiveID=gl_PrimitiveIDIn;
                        EmitVertex();
                    }
                    EndPrimitive();
                }
            }
            """;

    public static void run(Path output) throws Exception {
        var backend = BackendManager.RENDER_BACKEND;
        backend.onFrameBegin();
        try {
            check(SOURCE, false);
            check(SOURCE.replace("max_vertices=6", "max_vertices=3"), true);
            if (glGetError() != GL_NO_ERROR) throw new IllegalStateException("Geometry probe GL error");
            Files.writeString(
                    output.resolve("geometry.txt"),
                    "PASS\nchecks=GPU emission,six invocations,two input triangles,two strips,viewport/layer/primitive metadata,corner assembly,bounded overflow\n");
        } finally {
            backend.onFrameEnd();
        }
    }

    private static void check(String source, boolean overflow) {
        var kernel = GeometryCompute.lower(source, new GeometryCompute.Bindings(0, 1, 2, 4));
        var layout = kernel.layout();
        var sizes = layout.dispatch(2, 1024 * 1024);
        int input = glGenBuffers(),
                vertices = glGenBuffers(),
                counts = glGenBuffers(),
                corners = glGenBuffers(),
                control = glGenBuffers();
        int expansion = 0, assembly = 0;
        var inputBytes = MemoryUtil.memCalloc((int) sizes.inputBytes()).order(ByteOrder.nativeOrder());
        var vertexBytes = MemoryUtil.memAlloc((int) sizes.outputBytes()).order(ByteOrder.nativeOrder());
        var countBytes = MemoryUtil.memAlloc((int) sizes.countsBytes()).order(ByteOrder.nativeOrder());
        var cornerBytes = MemoryUtil.memAlloc((int) GeometryAssembly.cornerBytes(layout, sizes.jobs(), 1024 * 1024))
                .order(ByteOrder.nativeOrder());
        var parameters = MemoryUtil.memCalloc(16).order(ByteOrder.nativeOrder());
        try {
            for (int p = 0; p < 2; p++)
                for (int i = 0; i < 3; i++) {
                    int at = p * layout.inputStride() + i * 16;
                    inputBytes
                            .putFloat(at, p * 4 + i)
                            .putFloat(at + 4, i * .25f)
                            .putFloat(at + 8, 0)
                            .putFloat(at + 12, 1);
                    at = p * layout.inputStride() + 48 + i * 8;
                    inputBytes.putFloat(at, i * .125f).putFloat(at + 4, p * .25f);
                }
            allocate(input, 0, inputBytes);
            allocate(vertices, 1, sizes.outputBytes());
            allocate(counts, 2, sizes.countsBytes());
            allocate(corners, 3, cornerBytes.capacity());
            glBindBuffer(GL_UNIFORM_BUFFER, control);
            parameters.putInt(0, 2).putInt(12, 100);
            glBufferData(GL_UNIFORM_BUFFER, parameters, GL_DYNAMIC_DRAW);
            glBindBufferBase(GL_UNIFORM_BUFFER, 4, control);
            expansion = program(kernel.source());
            glUseProgram(expansion);
            glDispatchCompute(sizes.groups(), 1, 1);
            glMemoryBarrier(GL_SHADER_STORAGE_BARRIER_BIT | GL_BUFFER_UPDATE_BARRIER_BIT);
            read(vertices, vertexBytes);
            read(counts, countBytes);
            for (int job = 0; job < sizes.jobs(); job++) {
                int emitted = overflow ? 3 : 6;
                require(countBytes.getInt(job * 16) == emitted, "emitted count", job);
                require(countBytes.getInt(job * 16 + 4) == 2, "strip count", job);
                require(countBytes.getInt(job * 16 + 8) == (overflow ? 1 : 0), "overflow", job);
                for (int i = 0; i < emitted; i++) {
                    int at = (job * layout.maxVertices() + i) * layout.outputStride();
                    int primitive = job / 6, invocation = job % 6, part = i / 3, vertex = i % 3;
                    near(vertexBytes.getFloat(at), primitive * 4 + vertex, "position.x", job);
                    near(vertexBytes.getFloat(at + 4), vertex * .25f + part * .1f, "position.y", job);
                    near(vertexBytes.getFloat(at + 8), invocation * .1f, "position.z", job);
                    near(vertexBytes.getFloat(at + 12), 1, "position.w", job);
                    require(vertexBytes.getInt(at + 16) == invocation, "viewport", job);
                    require(vertexBytes.getInt(at + 20) == part, "layer", job);
                    require(vertexBytes.getInt(at + 24) == 100 + primitive, "primitive id", job);
                    require(vertexBytes.getInt(at + 28) == part, "strip id", job);
                    near(
                            vertexBytes.getFloat(at + layout.outputOffset("outgoing")),
                            vertex * .125f + invocation * .01f,
                            "varying.x",
                            job);
                    require(vertexBytes.getInt(at + layout.outputOffset("face")) == invocation, "flat varying", job);
                }
            }
            glBindBuffer(GL_UNIFORM_BUFFER, control);
            parameters.putInt(0, sizes.jobs()).putInt(4, 0).putInt(8, 0).putInt(12, 0);
            glBufferData(GL_UNIFORM_BUFFER, parameters, GL_DYNAMIC_DRAW);
            assembly = program(GeometryAssembly.source(layout, new GeometryAssembly.Bindings(1, 2, 3, 4)));
            glUseProgram(assembly);
            glDispatchCompute(sizes.groups(), 1, 1);
            glMemoryBarrier(GL_SHADER_STORAGE_BARRIER_BIT | GL_BUFFER_UPDATE_BARRIER_BIT);
            read(corners, cornerBytes);
            int perJob = GeometryAssembly.cornersPerJob(layout);
            for (int job = 0; job < sizes.jobs(); job++)
                for (int i = 0; i < perJob; i++) {
                    int at = (job * perJob + i) * 16;
                    if (overflow || i >= 6) {
                        for (int c = 0; c < 4; c++)
                            require(cornerBytes.getInt(at + c * 4) == -1, "invalid corner", job);
                    } else {
                        require(cornerBytes.getInt(at) == job * layout.maxVertices() + i, "corner index", job);
                        require(cornerBytes.getInt(at + 4) == job % 6, "corner viewport", job);
                        require(cornerBytes.getInt(at + 8) == i / 3, "corner layer", job);
                        require(cornerBytes.getInt(at + 12) == 100 + job / 6, "corner primitive", job);
                    }
                }
        } finally {
            glUseProgram(0);
            for (int i = 0; i < 4; i++) glBindBufferBase(GL_SHADER_STORAGE_BUFFER, i, 0);
            glBindBufferBase(GL_UNIFORM_BUFFER, 4, 0);
            for (int buffer : new int[] {input, vertices, counts, corners, control}) glDeleteBuffers(buffer);
            if (expansion != 0) glDeleteProgram(expansion);
            if (assembly != 0) glDeleteProgram(assembly);
            MemoryUtil.memFree(inputBytes);
            MemoryUtil.memFree(vertexBytes);
            MemoryUtil.memFree(countBytes);
            MemoryUtil.memFree(cornerBytes);
            MemoryUtil.memFree(parameters);
        }
    }

    private static void allocate(int buffer, int binding, long bytes) {
        glBindBuffer(GL_SHADER_STORAGE_BUFFER, buffer);
        glBufferData(GL_SHADER_STORAGE_BUFFER, bytes, GL_DYNAMIC_DRAW);
        glBindBufferBase(GL_SHADER_STORAGE_BUFFER, binding, buffer);
    }

    private static void allocate(int buffer, int binding, ByteBuffer bytes) {
        glBindBuffer(GL_SHADER_STORAGE_BUFFER, buffer);
        glBufferData(GL_SHADER_STORAGE_BUFFER, bytes, GL_DYNAMIC_DRAW);
        glBindBufferBase(GL_SHADER_STORAGE_BUFFER, binding, buffer);
    }

    private static void read(int buffer, ByteBuffer bytes) {
        glBindBuffer(GL_SHADER_STORAGE_BUFFER, buffer);
        glGetBufferSubData(GL_SHADER_STORAGE_BUFFER, 0, bytes);
    }

    private static int program(String source) {
        int shader = glCreateShader(GL_COMPUTE_SHADER), program = 0;
        try {
            glShaderSource(shader, source);
            glCompileShader(shader);
            if (glGetShaderi(shader, GL_COMPILE_STATUS) == GL_FALSE)
                throw new IllegalStateException(glGetShaderInfoLog(shader));
            program = glCreateProgram();
            glAttachShader(program, shader);
            glLinkProgram(program);
            if (glGetProgrami(program, GL_LINK_STATUS) == GL_FALSE)
                throw new IllegalStateException(glGetProgramInfoLog(program));
            return program;
        } catch (RuntimeException | Error failure) {
            if (program != 0) glDeleteProgram(program);
            throw failure;
        } finally {
            glDeleteShader(shader);
        }
    }

    private static void near(float actual, float expected, String field, int job) {
        require(Math.abs(actual - expected) < .00001f, field + "=" + actual + " expected=" + expected, job);
    }

    private static void require(boolean value, String field, int job) {
        if (!value) throw new IllegalStateException("Geometry job " + job + ": " + field);
    }
}
