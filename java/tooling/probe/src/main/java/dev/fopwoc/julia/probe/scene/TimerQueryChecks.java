package dev.fopwoc.julia.probe.scene;

import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL15.*;
import static org.lwjgl.opengl.GL30.*;
import static org.lwjgl.opengl.GL33.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.lwjgl.system.MemoryStack;

/** Real GPU timestamps, including unsubmitted markers and CPU delay excluded from GPU elapsed results. */
final class TimerQueryChecks {
    private TimerQueryChecks() {}

    static void run(Path output) throws IOException {
        int[] names = new int[3];
        java.util.function.Consumer<int[]> generate = org.lwjgl.opengl.GL15::glGenQueries;
        generate.accept(names);
        if (names[0] == 0 || names[0] == names[1] || glIsQuery(names[0]))
            throw new IllegalStateException("Query name reservation failed");
        // Previous scenes may resize or leave no writable default target. Time actual work on an owned attachment.
        int texture = glGenTextures(), fbo = glGenFramebuffers();
        glBindTexture(GL_TEXTURE_2D, texture);
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, 256, 256, 0, GL_RGBA, GL_UNSIGNED_BYTE, 0L);
        glBindFramebuffer(GL_FRAMEBUFFER, fbo);
        glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, texture, 0);
        glDisable(GL_SCISSOR_TEST);
        glColorMask(true, true, true, true);
        try {
            glQueryCounter(names[0], GL_TIMESTAMP);
            if (!glIsQuery(names[0]) || glGetQueryObjecti(names[0], GL_QUERY_RESULT_AVAILABLE) != 0)
                throw new IllegalStateException("Unsubmitted timestamp was prematurely available");
            long before = System.nanoTime();
            try {
                Thread.sleep(100);
            } catch (InterruptedException failure) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(failure);
            }
            glClearColor(0.1f, 0.2f, 0.3f, 1);
            glClear(GL_COLOR_BUFFER_BIT);
            glQueryCounter(names[1], GL_TIMESTAMP);
            long cpuGap = System.nanoTime() - before;
            long end = glGetQueryObjecti64(names[1], GL_QUERY_RESULT);
            long start = glGetQueryObjectui64(names[0], GL_QUERY_RESULT);
            long elapsed = end - start;
            if (start <= 0 || elapsed <= 0 || elapsed >= cpuGap / 2)
                throw new IllegalStateException(
                        "Expected real GPU time excluding CPU sleep: gpu=" + elapsed + " cpu=" + cpuGap);
            if (glGetQueryObjecti(names[1], GL_QUERY_RESULT_AVAILABLE) != 1)
                throw new IllegalStateException("Completed timestamp is unavailable");
            glBeginQuery(GL_TIME_ELAPSED, names[2]);
            glClearColor(0, 0, 0, 1);
            glClear(GL_COLOR_BUFFER_BIT);
            glEndQuery(GL_TIME_ELAPSED);
            long ranged = glGetQueryObjectui64(names[2], GL_QUERY_RESULT);
            if (ranged <= 0) throw new IllegalStateException("Elapsed timer returned no measured GPU time");
            for (int i = 0; i < 96; i++) glQueryCounter(names[0], GL_TIMESTAMP);
            if (glGetQueryObjectui64(names[0], GL_QUERY_RESULT) <= 0)
                throw new IllegalStateException("Reissued timestamp query lost its newest generation");
            try (var stack = MemoryStack.stackPush()) {
                var ids = stack.mallocInt(4);
                ids.position(1).limit(3);
                glGenQueries(ids);
                if (ids.position() != 1 || ids.limit() != 3 || ids.get(1) == ids.get(2))
                    throw new IllegalStateException("Query output changed buffer range");
                glDeleteQueries(ids.get(1));
                glDeleteQueries(ids.get(2));
            }
            Files.writeString(
                    output.resolve("timer-queries.txt"),
                    "PASS\ngpu_elapsed_ns=" + elapsed
                            + "\ncpu_gap_ns=" + cpuGap + "\nranged_elapsed_ns=" + ranged
                            + "\nchecks=GPU timestamps,availability,elapsed queries,reissue,lifetime,buffer positions\n");
        } finally {
            for (int name : names) glDeleteQueries(name);
            glBindFramebuffer(GL_FRAMEBUFFER, 0);
            glDeleteFramebuffers(fbo);
            glDeleteTextures(texture);
        }
        if (glIsQuery(names[0])) throw new IllegalStateException("Deleted query remains live");
    }
}
