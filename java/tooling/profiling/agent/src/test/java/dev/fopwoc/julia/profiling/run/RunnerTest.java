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

    private static final String SCENE =
            "{\"name\":\"s\",\"save\":\"Apocalypse/w\",\"jvmOptions\":[\"-Xmx8g\"],\"zombieBuddy\":true}";
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
                        + "\"vmArgs\":[\"-Xmx3072m\",\"-Dzomboid.steam=1\"]}");
        return GameInstall.read(game, "x86_64");
    }

    private RunPlan plan(String renderer, GameInstall game, Path buddyHome) throws IOException {
        return RunPlan.of(
                "label",
                renderer,
                root.resolve(renderer),
                root.resolve("template"),
                root.resolve("agent.jar"),
                game.workingDirectory().resolve("ZombieBuddy.jar"),
                buddyHome,
                Scene.parse(SCENE),
                game,
                List.of(),
                List.of());
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
    void aPlainSceneRunsWithoutSteamOrBuddy() throws Exception {
        var game = linuxGame();
        var scene = Scene.parse("{\"name\":\"s\",\"save\":\"Apocalypse/w\"}");
        assertFalse(scene.zombieBuddy());
        var plain = RunPlan.of(
                "label",
                "opengl",
                root.resolve("plain"),
                root.resolve("template"),
                root.resolve("agent.jar"),
                null,
                root.resolve("home"),
                scene,
                game,
                List.of(),
                List.of());
        assertEquals(
                1,
                plain.command().stream()
                        .filter(value -> value.startsWith("-javaagent:"))
                        .count());
        assertTrue(plain.command().contains("-Dzomboid.steam=0"));
        assertFalse(plain.command().contains("-Dzomboid.steam=1"));
        assertTrue(plain.command().stream().noneMatch(value -> value.startsWith("-Duser.home=")));
        var withSteam = Scene.parse("{\"name\":\"s\",\"save\":\"Apocalypse/w\",\"steam\":true}");
        assertTrue(RunPlan.of(
                        "label",
                        "opengl",
                        root.resolve("steam"),
                        root.resolve("template"),
                        root.resolve("agent.jar"),
                        null,
                        root.resolve("home"),
                        withSteam,
                        game,
                        List.of(),
                        List.of())
                .command()
                .contains("-Dzomboid.steam=1"));
    }

    @Test
    void preparingIsolatesEverythingAndLeavesOriginalsAlone() throws Exception {
        var template = root.resolve("template");
        var save = template.resolve("Saves/Apocalypse/w");
        Files.createDirectories(save);
        Files.createDirectories(template.resolve("mods"));
        Files.writeString(save.resolve("mods.txt"), ACTIVE);
        Files.writeString(
                template.resolve("mods/default.txt"),
                ACTIVE.replace("mod = Other,", "mod = Viewpoint,\n    mod = Other,"));
        Files.writeString(template.resolve("options.ini"), "width=1\nfocusloss=true\nvsync=true\n");
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
            assertFalse(text.contains("Viewpoint"), "Only the save's mods should run: " + text);
            assertTrue(text.contains("mod = Other,"));
            assertTrue(text.contains("map = Julia,"));
        }
        assertEquals(
                "width=1\nfocusloss=false\nvsync=false\n",
                Files.readString(plan.run().resolve("profile/options.ini")));
        assertEquals(
                "{\"trusted\":[1],\"preload_mods\":{},\"auto\":false}",
                Files.readString(plan.run().resolve("home/.zombie_buddy/config.json")));
        assertTrue(Files.readString(template.resolve("mods/default.txt")).contains("Viewpoint"));
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

    @Test
    void singleSideAndBaselineTables() throws Exception {
        var one = Report.summarize("s", List.of(capture("town", "julia", 60)));
        assertTrue(one.contains("| town | 1 | - | 60.0 | - | - | 20.00 |"), one);

        var batch = root.resolve("earlier");
        var saved = Files.createDirectories(batch.resolve("01-julia/captures/town"));
        Files.writeString(
                saved.resolve("summary.properties"), "status=COMPLETE\nframes=10\naverage_fps=50\np99_ns=20000000\n");
        Files.createDirectories(batch.resolve("01-opengl/captures/town"));
        var compared = new java.util.ArrayList<>(Report.baseline(batch));
        assertEquals(1, compared.size());
        compared.add(capture("town", "julia", 60));
        var table = Report.summarize("s", compared, Report.BUILDS, List.of("note"));
        assertTrue(table.contains("Baseline FPS"), table);
        assertTrue(table.contains("note"), table);
        assertTrue(table.contains("| town | 1 | 50.0 | 60.0 | +20.0% |"), table);
        assertThrows(
                IllegalArgumentException.class, () -> Report.baseline(Files.createDirectories(root.resolve("empty"))));
    }

    @Test
    void optionsNarrowTheSceneThatRunsRead() {
        var text =
                "{\"name\":\"s\",\"save\":\"Sandbox/w\",\"spots\":[{\"name\":\"a\"},{\"name\":\"b \\\"q\\\"\",\"ghost\":true},{\"name\":\"c\"}]}";
        assertEquals(text, ProfileCli.editScene(text, Map.of()));
        var edited = Scene.parse(ProfileCli.editScene(
                text.replace("b \\\"q\\\"", "b"), Map.of("--spot", "c,a", "--save", "Sandbox/other")));
        assertEquals("Sandbox/other", edited.save());
        assertEquals(
                List.of("a", "c"), edited.spots().stream().map(Scene.Spot::name).toList());
        assertThrows(IllegalArgumentException.class, () -> ProfileCli.editScene(text, Map.of("--spot", "missing")));
    }

    @Test
    void findsInstalledModsAndWhatTheyNeed() throws Exception {
        var workshop = root.resolve("workshop");
        var viewpoint = workshop.resolve("111/mods/Viewpoint");
        Files.createDirectories(viewpoint.resolve("common"));
        Files.createDirectories(viewpoint.resolve("42/media/java"));
        Files.writeString(viewpoint.resolve("common/mod.info"), "id=Viewpoint\njavaJarFile=media/java/v.jar\n");
        Files.createFile(viewpoint.resolve("42/media/java/v.jar"));
        var local = root.resolve("mods");
        Files.createDirectories(local.resolve("LuaOnly/42"));
        Files.writeString(local.resolve("LuaOnly/42/mod.info"), "id=LuaOnly\n");
        var catalog = ModCatalog.scan(workshop, local);
        var mods = catalog.resolve(List.of("Viewpoint", "LuaOnly"));
        assertTrue(mods.get(0).workshop());
        assertTrue(mods.get(0).jar().endsWith("42/media/java/v.jar"));
        assertFalse(mods.get(1).workshop());
        assertNull(mods.get(1).jar());
        assertThrows(IllegalArgumentException.class, () -> catalog.resolve(List.of("Nope")));
    }

    @Test
    void sceneModsBecomeTheActiveListAndJavaOnesApprovedInTheRunOnly() throws Exception {
        var template = root.resolve("template");
        var save = template.resolve("Saves/Apocalypse/w");
        Files.createDirectories(save);
        Files.createDirectories(template.resolve("mods"));
        Files.writeString(save.resolve("mods.txt"), ACTIVE);
        var home = root.resolve("real home");
        Files.createDirectories(home);
        Files.writeString(home.resolve("mod_approvals.json"), "real");
        Files.writeString(home.resolve("config.json"), "{}");
        var jar = Files.writeString(root.resolve("v.jar"), "content");
        var scene = Scene.parse(
                "{\"name\":\"s\",\"save\":\"Apocalypse/w\",\"mods\":[\"Viewpoint\",\"LuaOnly\"],\"zombieBuddy\":true}");
        var game = linuxGame();
        var plan = RunPlan.of(
                "label",
                "julia",
                root.resolve("run"),
                template,
                root.resolve("agent.jar"),
                game.workingDirectory().resolve("ZombieBuddy.jar"),
                home,
                scene,
                game,
                List.of(),
                List.of(new ModCatalog.Mod("Viewpoint", true, jar), new ModCatalog.Mod("LuaOnly", false, null)));
        plan.prepare();
        var active = Files.readString(plan.run().resolve("profile/mods/default.txt"));
        assertTrue(active.contains("mod = Viewpoint,\n    mod = LuaOnly,"), active);
        assertFalse(active.contains("mod = Other"), active);
        var approvals = Files.readString(plan.run().resolve("home/.zombie_buddy/mod_approvals.json"));
        assertTrue(approvals.contains("Viewpoint"), approvals);
        assertTrue(approvals.contains("ed7002b439e9ac845f22357d822bac1444730fbdb6016d3ec9432297b9ec9f73"), approvals);
        assertFalse(approvals.contains("LuaOnly"), approvals);
        assertEquals("real", Files.readString(home.resolve("mod_approvals.json")));
    }

    @Test
    void resourcesTableReadsWhatWasRecorded() throws Exception {
        var left = capture("town", "opengl", 50);
        var right = capture("town", "julia", 60);
        Files.writeString(
                left.directory().resolve("metrics.properties"),
                "heap_peak_mb=1000\nrss_mb=3000\ncpu_process_pct=210\ncpu_render_pct=90\ncpu_logic_pct=60\n");
        Files.writeString(
                right.directory().resolve("metrics.properties"),
                "heap_peak_mb=1200\ncpu_process_pct=190\ncpu_render_pct=70\ncpu_logic_pct=65\n");
        var table = Report.summarize("s", List.of(left, right));
        assertTrue(table.contains("| town | opengl | 1000 | 3000 | 210 | 90 | 60 |"), table);
        assertTrue(table.contains("| town | julia | 1200 | - | 190 | 70 | 65 |"), table);
    }
}
