package zombie.core.opengl;

/** Synthetic JVM fixture, not game code. */
public final class RenderThread {
    private static boolean rendered;

    private RenderThread() {}

    public static boolean lockStepRenderStep() {
        return rendered;
    }

    public static void main(String[] arguments) throws Exception {
        for (int i = 0; i < 600; i++) {
            if (i == 250
                    && arguments.length == 1
                    && java.nio.file.Files.exists(
                            java.nio.file.Path.of(arguments[0], "captures/fixture-forest/summary.properties")))
                throw new AssertionError("False render steps were recorded as frames");
            rendered = i > 250;
            lockStepRenderStep();
            Thread.sleep(2);
        }
    }
}
