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
import com.gtnewhorizons.angelica.sdlgpu.geometry.GeometryLayout;
import com.gtnewhorizons.angelica.sdlgpu.geometry.GeometryRaster;
import dev.fopwoc.julia.probe.adapter.ResourceReadback;
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
            GeometryDrawScene.run();
            GeometryPointScene.run(output);
            GeometryIndirectScene.run();
            check(SOURCE, false);
            check(SOURCE.replace("max_vertices=6", "max_vertices=3"), true);
            CullingChecks.run();
            if (glGetError() != GL_NO_ERROR) throw new IllegalStateException("Geometry probe GL error");
            Files.writeString(
                    output.resolve("geometry.txt"),
                    "PASS\nchecks=GPU-written indirect batches,instancing,divisors,base instance,draw IDs,GL geometry link/draw integration,normalized/indexed/disabled vertex fetch,shared uniforms,matrix arrays,six viewports,flat varyings,fragment primitive IDs,state restoration,bounds failure,relink/delete,GPU emission,two triangles,two strips,layer metadata,corner assembly,raster replay,routing discard,bounded overflow\n");
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
                            .putFloat(at, p * 8 + (i == 1 ? 3 : -1))
                            .putFloat(at + 4, i == 2 ? 3 : -1)
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
                    near(vertexBytes.getFloat(at), primitive * 8 + (vertex == 1 ? 3 : -1), "position.x", job);
                    near(vertexBytes.getFloat(at + 4), (vertex == 2 ? 3 : -1) + part * .1f, "position.y", job);
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
            if (!overflow) raster(layout, vertices, corners, control, parameters, sizes.jobs() * perJob);
        } finally {
            glUseProgram(0);
            for (int i = 0; i < 5; i++) glBindBufferBase(GL_SHADER_STORAGE_BUFFER, i, 0);
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

    private static void raster(
            GeometryLayout layout, int vertices, int corners, int control, ByteBuffer parameters, int count) {
        String vertex = GeometryRaster.source(layout, new GeometryRaster.Bindings(1, 3, 4));
        int program = program(new int[] {GL_VERTEX_SHADER, GL_FRAGMENT_SHADER}, new String[] {vertex, """
                #version 460 core
                flat in int face;
                flat in int _geometry_primitive_id;
                layout(location=0) out vec4 color;
                void main() { color=vec4(float(face)/255.0,float(_geometry_primitive_id)/255.0,0,1); }
                """});
        int texture = glGenTextures(), fbo = glGenFramebuffers(), vao = glGenVertexArrays();
        try {
            glBindTexture(GL_TEXTURE_2D, texture);
            glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, 8, 8, 0, GL_RGBA, GL_UNSIGNED_BYTE, (ByteBuffer) null);
            glBindFramebuffer(GL_FRAMEBUFFER, fbo);
            glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, texture, 0);
            require(glCheckFramebufferStatus(GL_FRAMEBUFFER) == GL_FRAMEBUFFER_COMPLETE, "raster framebuffer", 0);
            glBindVertexArray(vao);
            glViewport(0, 0, 8, 8);
            glDisable(GL_DEPTH_TEST);
            glDisable(GL_CULL_FACE);
            glDisable(GL_BLEND);
            glDisable(GL_SCISSOR_TEST);
            glDisable(GL_STENCIL_TEST);
            glColorMask(true, true, true, true);
            glUseProgram(program);
            glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 4, control);
            glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 1, vertices);
            glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 3, corners);
            for (int selected : new int[] {3, 6}) {
                parameters.putInt(0, selected).putInt(4, 1).putInt(8, 0).putInt(12, 0);
                glBindBuffer(GL_SHADER_STORAGE_BUFFER, control);
                glBufferData(GL_SHADER_STORAGE_BUFFER, parameters, GL_DYNAMIC_DRAW);
                glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 4, control);
                glClearColor(0, 0, 0, 1);
                glClear(GL_COLOR_BUFFER_BIT);
                glDrawArrays(GL_TRIANGLES, 0, count);
                byte[] image = ResourceReadback.layer(texture, 0);
                int pixel = (4 * 8 + 4) * 4;
                require(
                        (image[pixel] & 255) == (selected == 3 ? 3 : 0)
                                && (image[pixel + 1] & 255) == (selected == 3 ? 100 : 0)
                                && (image[pixel + 2] & 255) == 0
                                && (image[pixel + 3] & 255) == 255,
                        "raster viewport selection " + selected + " rgba=" + (image[pixel] & 255) + ","
                                + (image[pixel + 1] & 255) + "," + (image[pixel + 2] & 255) + ","
                                + (image[pixel + 3] & 255),
                        0);
            }
        } finally {
            glUseProgram(0);
            glBindFramebuffer(GL_FRAMEBUFFER, 0);
            glBindVertexArray(0);
            glDeleteVertexArrays(vao);
            glDeleteFramebuffers(fbo);
            glDeleteTextures(texture);
            glDeleteProgram(program);
        }
    }

    private static int program(String source) {
        return program(new int[] {GL_COMPUTE_SHADER}, new String[] {source});
    }

    private static int program(int[] types, String[] sources) {
        int program = glCreateProgram();
        int[] shaders = new int[types.length];
        try {
            for (int i = 0; i < types.length; i++) {
                shaders[i] = glCreateShader(types[i]);
                glShaderSource(shaders[i], sources[i]);
                glCompileShader(shaders[i]);
                if (glGetShaderi(shaders[i], GL_COMPILE_STATUS) == GL_FALSE)
                    throw new IllegalStateException(glGetShaderInfoLog(shaders[i]));
                glAttachShader(program, shaders[i]);
            }
            glLinkProgram(program);
            if (glGetProgrami(program, GL_LINK_STATUS) == GL_FALSE)
                throw new IllegalStateException(glGetProgramInfoLog(program));
            return program;
        } catch (RuntimeException | Error failure) {
            glDeleteProgram(program);
            throw failure;
        } finally {
            for (int shader : shaders) if (shader != 0) glDeleteShader(shader);
        }
    }

    private static void near(float actual, float expected, String field, int job) {
        require(Math.abs(actual - expected) < .00001f, field + "=" + actual + " expected=" + expected, job);
    }

    private static void require(boolean value, String field, int job) {
        if (!value) throw new IllegalStateException("Geometry job " + job + ": " + field);
    }
}
