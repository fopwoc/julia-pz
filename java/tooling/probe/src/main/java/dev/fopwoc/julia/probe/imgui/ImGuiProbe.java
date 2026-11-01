package dev.fopwoc.julia.probe.imgui;

import dev.fopwoc.julia.backend.runtime.GlsmSession;
import dev.fopwoc.julia.backend.runtime.HostedVideo;
import dev.fopwoc.julia.probe.scene.ImGuiScene;
import java.lang.reflect.InvocationTargetException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Windowless check of the archive's actual ImGui JNI, font atlas, and prepared GL renderer. */
public final class ImGuiProbe {
    private static final Class<?>[] NONE = {};
    private static final Class<?>[] XY = {float.class, float.class};
    private static final Class<?>[] IMAGE = {int.class, float.class, float.class, float.class, float.class};
    private static final Class<?>[] CLIP = {float.class, float.class, float.class, float.class};
    private static final Class<?>[] RECT = {float.class, float.class, float.class, float.class, int.class};

    private ImGuiProbe() {}

    public static void run(Path destination, Path archive, Path natives) throws Exception {
        Path output = destination.toAbsolutePath();
        Files.createDirectories(output);
        Files.deleteIfExists(output.resolve("result.txt"));
        String previous = System.getProperty("imgui.library.path");
        System.setProperty("imgui.library.path", natives.toAbsolutePath().toString());
        try (var loader = new ImGuiArchiveLoader(archive, ImGuiProbe.class.getClassLoader());
                var video = HostedVideo.start();
                var session = GlsmSession.open(video, 0, () -> new GlsmSession.Size(32, 32), "Julia ImGui probe")) {
            var imgui = loader.loadClass("imgui.ImGui");
            call(imgui, "createContext", NONE);
            try {
                var io = call(imgui, "getIO", NONE);
                call(io, "setIniFilename", new Class<?>[] {String.class}, (Object) null);
                call(io, "setDisplaySize", XY, 16f, 16f);
                call(io, "setDisplayFramebufferScale", XY, 2f, 2f);
                call(io, "setDeltaTime", new Class<?>[] {float.class}, 1f / 60);
                var renderer = loader.loadClass("imgui.gl3.ImGuiImplGl3")
                        .getConstructor()
                        .newInstance();
                session.backend().onFrameBegin();
                try {
                    call(renderer, "init", new Class<?>[] {String.class}, "#version 410 core");
                    try {
                        for (int draw = 0; draw < 2; draw++)
                            ImGuiScene.verify(texture -> draw(imgui, renderer, texture));
                    } finally {
                        call(renderer, "dispose", NONE);
                    }
                } finally {
                    session.backend().onFrameEnd();
                }
            } finally {
                call(imgui, "destroyContext", NONE);
            }
        } finally {
            if (previous == null) System.clearProperty("imgui.library.path");
            else System.setProperty("imgui.library.path", previous);
        }
        Files.writeString(output.resolve("result.txt"), """
                PASS
                verified_pixels=2048
                draws=2
                renderer=archive ImGuiImplGl3, prepared GL calls through isolated typed dispatch
                data=archive ImGui JNI, owned draw data freed after rendering
                checks=font atlas,texture switching,indexed colored vertices,scissor clipping,2x framebuffer scale,caller state restoration,repeated draw data ownership
                window=none
                """);
        System.out.println("[Julia] PASS: archive ImGui renderer; 2048 pixels verified without a window");
    }

    private static void draw(Class<?> imgui, Object renderer, int texture) {
        call(imgui, "newFrame", NONE);
        var list = call(imgui, "getBackgroundDrawList", NONE);
        call(list, "addImage", IMAGE, texture, 0f, 0f, 16f, 16f);
        call(list, "pushClipRect", CLIP, 4f, 3f, 12f, 11f);
        call(list, "addRectFilled", RECT, 2f, 2f, 14f, 14f, 0xff0000ff);
        call(list, "popClipRect", NONE);
        call(imgui, "render", NONE);
        var data = call(imgui, "getDrawData", NONE);
        var signature = new Class<?>[] {data.getClass()};
        try {
            call(renderer, "renderDrawData", signature, data);
        } finally {
            call(imgui, "freeDrawData", signature, data);
        }
    }

    private static Object call(Object target, String name, Class<?>[] signature, Object... arguments) {
        var type = target instanceof Class<?> staticType ? staticType : target.getClass();
        try {
            return type.getMethod(name, signature).invoke(target instanceof Class<?> ? null : target, arguments);
        } catch (InvocationTargetException failure) {
            var cause = failure.getCause();
            if (cause instanceof RuntimeException exception) throw exception;
            if (cause instanceof Error error) throw error;
            throw new IllegalStateException("ImGui probe failed at " + name, cause);
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("Archive ImGui API does not match " + name, failure);
        }
    }
}
