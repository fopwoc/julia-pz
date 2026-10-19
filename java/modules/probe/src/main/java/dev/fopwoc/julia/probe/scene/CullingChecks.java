package dev.fopwoc.julia.probe.scene;

import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL20.*;
import static org.lwjgl.opengl.GL30.*;

import com.gtnewhorizons.angelica.sdlgpu.SDLGPUGate;
import dev.fopwoc.julia.backend.BackendInternals;
import dev.fopwoc.julia.probe.adapter.ResourceReadback;
import java.nio.ByteBuffer;
import java.util.Arrays;
import org.lwjgl.system.MemoryStack;

/** A known CCW triangle must preserve GL face classification and culling when rendered into a texture. */
final class CullingChecks {
    private CullingChecks() {}

    static void run() {
        int vs = glCreateShader(GL_VERTEX_SHADER), fs = glCreateShader(GL_FRAGMENT_SHADER), program = glCreateProgram();
        int target = glGenTextures(), fbo = glGenFramebuffers();
        boolean ownedFinalTarget = false;
        try {
            glShaderSource(vs, """
                    #version 330 core
                    void main() {
                        vec2 p = vec2((gl_VertexID << 1) & 2, gl_VertexID & 2);
                        gl_Position = vec4(p * 2.0 - 1.0, 0, 1);
                    }
                    """);
            glShaderSource(fs, """
                    #version 330 core
                    out vec4 color;
                    void main() { color = gl_FrontFacing ? vec4(0, 1, 0, 1) : vec4(1, 0, 0, 1); }
                    """);
            for (int shader : new int[] {vs, fs}) {
                glCompileShader(shader);
                if (glGetShaderi(shader, GL_COMPILE_STATUS) == GL_FALSE)
                    throw new IllegalStateException(glGetShaderInfoLog(shader));
                glAttachShader(program, shader);
            }
            glLinkProgram(program);
            if (glGetProgrami(program, GL_LINK_STATUS) == GL_FALSE)
                throw new IllegalStateException(glGetProgramInfoLog(program));
            glUseProgram(program);
            glBindTexture(GL_TEXTURE_2D, target);
            glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, 8, 8, 0, GL_RGBA, GL_UNSIGNED_BYTE, (ByteBuffer) null);
            glBindFramebuffer(GL_FRAMEBUFFER, fbo);
            glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, target, 0);
            if (glCheckFramebufferStatus(GL_FRAMEBUFFER) != GL_FRAMEBUFFER_COMPLETE)
                throw new IllegalStateException("Incomplete culling framebuffer");
            glViewport(0, 0, 8, 8);
            glDisable(GL_BLEND);
            glDisable(GL_DEPTH_TEST);
            glDisable(GL_SCISSOR_TEST);
            glColorMask(true, true, true, true);
            glClearColor(0, 0, 1, 1);
            // A windowless session has no final target until one is explicitly allocated.
            var resources = BackendInternals.resources();
            if (!BackendInternals.frames().finalTarget().isReady()) {
                ownedFinalTarget = true;
                BackendInternals.frames()
                        .finalTarget()
                        .create(SDLGPUGate.device(), resources, 8, 8, resources.mapTextureFormat(GL_RGBA8));
            }
            for (int framebuffer : new int[] {fbo, 0}) {
                glBindFramebuffer(GL_FRAMEBUFFER, framebuffer);
                for (int front : new int[] {GL_CCW, GL_CW}) {
                    glFrontFace(front);
                    for (int cull : new int[] {0, GL_BACK, GL_FRONT}) {
                        if (cull == 0) glDisable(GL_CULL_FACE);
                        else {
                            glEnable(GL_CULL_FACE);
                            glCullFace(cull);
                        }
                        glClear(GL_COLOR_BUFFER_BIT);
                        glDrawArrays(GL_TRIANGLES, 0, 3);
                        boolean isFront = front == GL_CCW;
                        boolean culled = (cull == GL_FRONT && isFront) || (cull == GL_BACK && !isFront);
                        int[] expected = culled
                                ? new int[] {0, 0, 255, 255}
                                : isFront ? new int[] {0, 255, 0, 255} : new int[] {255, 0, 0, 255};
                        byte[] pixels;
                        if (framebuffer != 0) pixels = ResourceReadback.layer(target, 0);
                        else
                            try (var stack = MemoryStack.stackPush()) {
                                var data = stack.malloc(8 * 8 * 4);
                                glReadPixels(0, 0, 8, 8, GL_RGBA, GL_UNSIGNED_BYTE, data);
                                pixels = new byte[data.remaining()];
                                data.get(pixels);
                            }
                        for (int i = 0; i < 64; i++)
                            for (int c = 0; c < 4; c++)
                                if ((pixels[i * 4 + c] & 255) != expected[c])
                                    throw new IllegalStateException("Culling framebuffer=" + framebuffer + " front="
                                            + front + " cull=" + cull
                                            + " pixel=" + i + " channel=" + c + " got=" + (pixels[i * 4 + c] & 255)
                                            + " expected=" + expected[c] + " rgba="
                                            + Arrays.toString(Arrays.copyOfRange(pixels, i * 4, i * 4 + 4)));
                    }
                }
            }
        } finally {
            glDisable(GL_CULL_FACE);
            glFrontFace(GL_CCW);
            glCullFace(GL_BACK);
            glUseProgram(0);
            glBindFramebuffer(GL_FRAMEBUFFER, 0);
            glBindTexture(GL_TEXTURE_2D, 0);
            if (ownedFinalTarget) BackendInternals.frames().destroyFinalTarget();
            glDeleteFramebuffers(fbo);
            glDeleteTextures(target);
            glDeleteProgram(program);
            glDeleteShader(vs);
            glDeleteShader(fs);
        }
    }
}
