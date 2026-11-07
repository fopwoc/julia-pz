package dev.fopwoc.julia.profiling.run;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RunnerTest {
    @TempDir
    Path root;

    private static final String SCENE = "{\"name\":\"s\",\"save\":\"Apocalypse/w\",\"jvmOptions\":[\"-Xmx8g\"]}";
    private static final String ACTIVE =
            "VERSION = 1,\nmods\n{\n    mod = Other,\n    mod = Julia,\n    mod = JuliaDev,\n}\nmaps\n{\n    map = Julia,\n}\n";

    private GameInstall linuxGame() throws IOException {
        var game = root.resolve("game with spaces");
        Files.createDirectories(game.resolve("jre64/bin"));
        Files.createFile(game.resolve("projectzomboid.jar"));
        Files.createFile(game.resolve("jre64/bin/java"));
        Files.createFile(game.resolve("ZombieBuddy.jar"));
        Files.writeString(
                game.resolve("ProjectZomboid64.json"),
                "{\"mainClass\":\"zombie/gameStates/MainScreenState\",\"classpath\":[\".\",\"projectzomboid.jar\"],"
                        + "\"vmArgs\":[\"-Xmx3072m\"]}");
        return GameInstall.read(game, "x86_64");
    }

    private RunPlan plan(String renderer, GameInstall game, Path buddyHome) throws IOException {
        return RunPlan.of(
                renderer,
                root.resolve(renderer),
                root.resolve("template"),
                root.resolve("agent.jar"),
                game.workingDirectory().resolve("ZombieBuddy.jar"),
                buddyHome,
                Scene.parse(SCENE),
                game);
    }

    @Test
    void parsesJson() {
        assertEquals(
                Map.of("a", List.of(1.0, "x\n\"", true), "b", Map.of()),
                Json.parse(" {\"a\": [1, \"x\\n\\\"\", true], \"b\": {}} "));
        for (var invalid : List.of("{", "[1,]", "{\"a\" 1}", "1 2", "nope"))
            assertThrows(IllegalArgumentException.class, () -> Json.parse(invalid), invalid);
    }

    @Test
    void readsScenesAndRejectsBadOnes() {
        var scene = Scene.parse("{\"name\":\"s\",\"save\":\"Sandbox/my world\",\"warmup\":1,\"duration\":2,\"spots\":["
                + "{\"name\":\"a\",\"position\":[1.5,2,0],\"hour\":12,\"ghost\":true},{\"name\":\"b\"}]}");
        assertEquals("Sandbox", scene.mode());
        assertEquals("my world", scene.world());
        assertEquals(new Scene.Position(1.5f, 2, 0), scene.spots().getFirst().position());
        assertTrue(scene.spots().getFirst().ghost());
        // Without spots it records wherever the save left the player.
        assertEquals("s", Scene.parse(SCENE).spots().getFirst().name());
        for (var invalid : List.of(
                "{\"name\":\"s\",\"save\":\"../w\"}",
                "{\"name\":\"s\",\"save\":\"Multiplayer/w\"}",
                "{\"name\":\"a/b\",\"save\":\"Sandbox/w\"}",
                "{\"name\":\"s\",\"save\":\"Sandbox/w\",\"unknown\":1}",
                "{\"name\":\"s\",\"save\":\"Sandbox/w\",\"warmup\":-1}",
                "{\"name\":\"s\",\"save\":\"Sandbox/w\",\"duration\":0}",
                "{\"name\":\"s\",\"save\":\"Sandbox/w\",\"spots\":[]}",
                "{\"name\":\"s\",\"save\":\"Sandbox/w\",\"spots\":[{\"name\":\"a\"},{\"name\":\"a\"}]}",
                "{\"name\":\"s\",\"save\":\"Sandbox/w\",\"spots\":[{\"name\":\"a\",\"position\":[1,2,0.5]}]}",
                "{\"name\":\"s\",\"save\":\"Sandbox/w\",\"spots\":[{\"name\":\"a\",\"hour\":24}]}",
                "{\"name\":\"s\",\"save\":\"Sandbox/w\",\"spots\":[{\"name\":\"a\",\"ghost\":1}]}")) {
            assertThrows(IllegalArgumentException.class, () -> Scene.parse(invalid), invalid);
        }
    }

    @Test
    void readsTheMacBundleSettings() throws Exception {
        var contents = root.resolve("Project Zomboid.app/Contents");
        Files.createDirectories(contents.resolve("Java"));
        Files.createDirectories(contents.resolve("PlugIns/jre-aarch64/Contents/Home/bin"));
        Files.createFile(contents.resolve("Java/projectzomboid.jar"));
        Files.createFile(contents.resolve("PlugIns/jre-aarch64/Contents/Home/bin/java"));
        Files.writeString(contents.resolve("Info.plist"), """
                <plist><dict>
                <key>JVMMainClassName</key>
                <string>zombie.gameStates.MainScreenState</string>
                <key>JVMOptions</key>
                <array>
                    <string>-XstartOnFirstThread</string>
                    <string>-Xmx3072m</string>
                </array>
                </dict></plist>
                """);
        var install = GameInstall.read(root, "aarch64");
        assertEquals(contents.resolve("Java"), install.workingDirectory());
        assertTrue(install.options().contains("-XstartOnFirstThread"));
        assertTrue(install.java().endsWith("jre-aarch64/Contents/Home/bin/java"));
        assertEquals("zombie.gameStates.MainScreenState", install.mainClass());
        assertThrows(IllegalArgumentException.class, () -> GameInstall.read(root.resolve("missing"), "aarch64"));
    }

    @Test
    void agentComesBeforeBuddyAndSelectsTheRenderer() throws Exception {
        var game = linuxGame();
        var plan = plan("julia", game, root.resolve("home"));
        var agents = plan.command().stream()
                .filter(value -> value.startsWith("-javaagent:"))
                .toList();
        assertEquals("-javaagent:" + root.resolve("agent.jar") + "=julia," + root.resolve("julia"), agents.getFirst());
        assertTrue(agents.get(1).endsWith("ZombieBuddy.jar"));
        assertTrue(plan.command().contains("-Duser.home=" + root.resolve("julia/home")));
        assertTrue(plan.command().indexOf("-Xmx8g") > plan.command().indexOf("-Xmx3072m"), "The scene must win");
        assertTrue(plan.command().contains("-cachedir=" + root.resolve("julia/profile")));
        assertFalse(Files.exists(plan.run()), "Planning must touch nothing");
    }

    @Test
    void preparingIsolatesEverythingAndLeavesOriginalsAlone() throws Exception {
        var template = root.resolve("template");
        var save = template.resolve("Saves/Apocalypse/w");
        Files.createDirectories(save);
        Files.createDirectories(template.resolve("mods"));
        Files.writeString(save.resolve("mods.txt"), ACTIVE);
        Files.writeString(template.resolve("mods/default.txt"), ACTIVE);
        Files.writeString(template.resolve("options.ini"), "width=1\nfocusloss=true\n");
        var home = root.resolve("real home");
        Files.createDirectories(home);
        var config = "{\"trusted\":[1],\"preload_mods\":{\"JuliaDev\":{\"jarPath\":\"/real{x}.jar\"}},\"auto\":false}";
        Files.writeString(home.resolve("config.json"), config);

        var plan = plan("opengl", linuxGame(), home);
        plan.prepare();

        for (var active : List.of(
                plan.run().resolve("profile/mods/default.txt"),
                plan.run().resolve("profile/Saves/Apocalypse/w/mods.txt"))) {
            var text = Files.readString(active);
            assertFalse(text.contains("mod = Julia"), text);
            assertTrue(text.contains("mod = Other,"));
            assertTrue(text.contains("map = Julia,"));
        }
        assertEquals("width=1\nfocusloss=false\n", Files.readString(plan.run().resolve("profile/options.ini")));
        assertEquals(
                "{\"trusted\":[1],\"preload_mods\":{},\"auto\":false}",
                Files.readString(plan.run().resolve("home/.zombie_buddy/config.json")));
        assertEquals(ACTIVE, Files.readString(template.resolve("mods/default.txt")));
        assertEquals(config, Files.readString(home.resolve("config.json")));
    }

    @Test
    void bakeKeepsOnlyWhatARunNeedsAndNeverOverwrites() throws Exception {
        var source = root.resolve("Zomboid");
        var save = source.resolve("Saves/Apocalypse/w");
        Files.createDirectories(save);
        Files.createDirectories(source.resolve("Saves/Apocalypse/other"));
        Files.createDirectories(source.resolve("mods/LocalMod"));
        Files.createFile(save.resolve("map_ver.bin"));
        Files.createFile(source.resolve("mods/default.txt"));
        Files.createFile(source.resolve("mods/reset-mods-42_00.txt"));
        Files.createFile(source.resolve("options.ini"));
        var output = root.resolve("template");
        Bake.bake(source, "Apocalypse/w", output);
        assertTrue(Files.isRegularFile(output.resolve("Saves/Apocalypse/w/map_ver.bin")));
        assertTrue(Files.isRegularFile(output.resolve("mods/reset-mods-42_00.txt")));
        assertTrue(Files.isRegularFile(output.resolve("options.ini")));
        assertFalse(Files.exists(output.resolve("Saves/Apocalypse/other")));
        assertFalse(Files.exists(output.resolve("mods/LocalMod")));
        assertThrows(IllegalArgumentException.class, () -> Bake.bake(source, "Apocalypse/w", output));
        assertThrows(IllegalArgumentException.class, () -> Bake.bake(source, "Apocalypse/none", root.resolve("other")));
    }

    private Report.Capture capture(String spot, String renderer, double fps) throws IOException {
        var directory = Files.createDirectories(root.resolve(spot + "-" + renderer + "-" + fps));
        Files.writeString(
                directory.resolve("summary.properties"),
                "status=COMPLETE\nscenario=" + spot + "\nwarmup_ms=1\nduration_ms=2\nframes=10\naverage_fps=" + fps
                        + "\np99_ns=20000000\n");
        return new Report.Capture(spot, renderer, directory);
    }

    @Test
    void summaryComparesPerSpotAndFlagsASinglePair() throws Exception {
        var single = Report.summarize("s", List.of(capture("town", "opengl", 50), capture("town", "julia", 60)));
        assertTrue(single.contains("| town | 1 | 50.0 | 60.0 | +20.0% | 20.00 | 20.00 |"), single);
        assertTrue(single.contains("run-to-run noise"), single);

        var repeated = Report.summarize(
                "s",
                List.of(
                        capture("town", "opengl", 50),
                        capture("town", "opengl", 54),
                        capture("town", "opengl", 52),
                        capture("town", "julia", 60),
                        capture("town", "julia", 66),
                        capture("town", "julia", 62)));
        assertTrue(repeated.contains("| town | 3 | 52.0 (50.0-54.0) | 62.0 (60.0-66.0) |"), repeated);
        assertFalse(repeated.contains("run-to-run noise"), repeated);
    }

    @Test
    void comparisonRefusesIncompleteOrDifferentCaptures() throws Exception {
        var baseline = capture("town", "opengl", 50);
        var other = capture("forest", "julia", 60);
        assertThrows(IllegalArgumentException.class, () -> Report.compare(baseline.directory(), other.directory()));
        var stopped = capture("town", "julia", 61);
        Files.writeString(stopped.directory().resolve("summary.properties"), "status=STOPPED\nframes=10\n");
        assertThrows(IllegalArgumentException.class, () -> Report.compare(baseline.directory(), stopped.directory()));
    }
}
