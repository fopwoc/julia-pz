package dev.fopwoc.julia.probe.scene;

import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL20.*;
import static org.lwjgl.opengl.GL30.*;

import dev.fopwoc.julia.backend.Upstream;
import dev.fopwoc.julia.backend.runtime.DefaultTarget;
import dev.fopwoc.julia.probe.adapter.ResourceReadback;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Approval and game frames can use different logical dimensions in one native window. */
final class DefaultTargetChecks {
    private DefaultTargetChecks() {}

    static void run(Path output) throws IOException {
        var frames = Upstream.frames();
        var target = frames.finalTarget();
        boolean owned = !target.isReady();
        int oldWidth = target.width(), oldHeight = target.height();
        int program = program();
        try {
            for (int width : new int[] {32, 16, 32}) {
                int height = width * 3 / 4;
                DefaultTarget.resize(width, height);
                if (frames.getFbo0Width() != width || frames.getFbo0Height() != height)
                    throw new IllegalStateException("Default target did not follow the frame dimensions");
                glBindFramebuffer(GL_FRAMEBUFFER, 0);
                glViewport(0, 0, width, height);
                glDisable(GL_DEPTH_TEST);
                glDisable(GL_BLEND);
                glDisable(GL_SCISSOR_TEST);
                glUseProgram(program);
                glDrawArrays(GL_TRIANGLES, 0, 3);
                int texture = Upstream.resources().getFbo(target.fboId()).colorGlIds[0];
                byte[] pixels = ResourceReadback.layer(texture, 0);
                if (pixels.length != width * height * 4)
                    throw new IllegalStateException("Default framebuffer readback dimensions differ");
                for (byte pixel : pixels)
                    if ((pixel & 255) != 255)
                        throw new IllegalStateException("Sized frame did not cover its entire default target");
            }
            Files.writeString(
                    output.resolve("default-target.txt"),
                    "PASS\nchecks=approval/game/menu size transitions,full target coverage\n");
        } finally {
            glUseProgram(0);
            glDeleteProgram(program);
            if (owned) frames.destroyFinalTarget();
            else DefaultTarget.resize(oldWidth, oldHeight);
        }
    }

    private static int program() {
        int program = glCreateProgram();
        for (int type : new int[] {GL_VERTEX_SHADER, GL_FRAGMENT_SHADER}) {
            int shader = glCreateShader(type);
            try {
                glShaderSource(
                        shader,
                        type == GL_VERTEX_SHADER
                                ? "#version 430 core\nvoid main(){vec2 p=vec2((gl_VertexID<<1)&2,gl_VertexID&2);gl_Position=vec4(p*2.-1.,0,1);}"
                                : "#version 430 core\nout vec4 colour;void main(){colour=vec4(1);}");
                glCompileShader(shader);
                if (glGetShaderi(shader, GL_COMPILE_STATUS) == 0)
                    throw new IllegalStateException(glGetShaderInfoLog(shader));
                glAttachShader(program, shader);
            } finally {
                glDeleteShader(shader);
            }
        }
        glLinkProgram(program);
        if (glGetProgrami(program, GL_LINK_STATUS) == 0) throw new IllegalStateException(glGetProgramInfoLog(program));
        return program;
    }
}
