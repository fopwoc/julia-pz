package dev.fopwoc.julia.profiling.agent;

import static org.junit.jupiter.api.Assertions.*;

import dev.fopwoc.julia.profiling.run.Scene;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AutomationTest {
    @TempDir
    Path run;

    private final List<String> log = new ArrayList<>();
    private boolean menu = true, playerReady, arrived = true;
    private String recording = "COMPLETE";
    private String backend;
    private int turns;
    private long clock;
    private List<Row> drawn;
    private List<String> loaded = List.of();
    private int kept;
    private int screams;

    private final Game game = new Game() {
        @Override
        public boolean atMainMenu() {
            return menu;
        }

        @Override
        public void loadSave(String mode, String world) {
            log.add("load " + mode + "/" + world);
        }

        @Override
        public boolean playerReady() {
            return playerReady;
        }

        @Override
        public void arrive(Scene.Spot spot) {
            log.add("arrive " + spot.name());
        }

        @Override
        public boolean arrived(Scene.Spot spot) {
            return arrived;
        }

        @Override
        public void face(float degrees) {
            turns++;
        }

        @Override
        public List<String> loadedMods() {
            return loaded;
        }

        @Override
        public void firstPerson() {
            log.add("first person");
        }

        @Override
        public void scream() {
            screams++;
        }

        @Override
        public String machine() {
            return "machine";
        }

        @Override
        public String position() {
            return "1,2,0";
        }

        @Override
        public int zoomOut() {
            return 250;
        }

        @Override
        public void keepAlive() {
            kept++;
        }

        @Override
        public void drawPanel(List<Row> rows) {
            drawn = rows;
        }

        @Override
        public void quit() {
            log.add("quit");
        }
    };

    private final Automation.Recorder recorder = new Automation.Recorder() {
        @Override
        public void record(String name, Duration warmup, Duration duration) {
            log.add("record " + name);
        }

        @Override
        public String finished() {
            return recording;
        }
    };

    private Scene scene() throws IOException {
        var file = run.resolve("scene.json");
        Files.writeString(
                file,
                "{\"name\":\"s\",\"save\":\"Sandbox/w\",\"warmup\":20,\"duration\":60,\"spots\":[{\"name\":\"a\"},{\"name\":\"b\",\"ghost\":true}]}");
        return Scene.read(file);
    }

    private Automation automation(boolean julia) throws IOException {
        return new Automation(
                scene(),
                game,
                recorder,
                run.resolve("automation.txt"),
                julia,
                () -> backend,
                "run 1/2 opengl",
                () -> "Test GPU",
                () -> List.of(new Row("Heap", "1 MB")));
    }

    private void ticks(Automation automation, int count) {
        for (int at = 0; at < count; at++)
            automation.tick(clock += Duration.ofSeconds(1).toNanos());
    }

    private String status() throws IOException {
        return Files.readString(run.resolve("automation.txt")).strip();
    }

    @Test
    void visitsEverySpotInOrderThenQuitsOnce() throws Exception {
        var automation = automation(false);
        ticks(automation, 2);
        assertEquals(List.of("load Sandbox/w"), log);
        assertEquals("LOADING", status());
        playerReady = true;
        ticks(automation, 60);
        assertEquals(
                List.of(
                        "load Sandbox/w",
                        "arrive a",
                        "arrive b",
                        "arrive a",
                        "record a",
                        "arrive b",
                        "record b",
                        "quit"),
                log);
        assertTrue(turns > 0, "The character must look around before recording");
        assertTrue(kept > 1, "Staying alive must be reapplied through the run");
        assertEquals("DONE", status());
    }

    @Test
    void waitsForTheMenuAndAsksForTheSaveOnlyOnce() throws Exception {
        menu = false;
        var automation = automation(false);
        ticks(automation, 3);
        assertTrue(log.isEmpty());
        menu = true;
        ticks(automation, 5);
        assertEquals(List.of("load Sandbox/w"), log);
    }

    @Test
    void refusesToRecordOnTheWrongRenderer() throws Exception {
        playerReady = true;
        var julia = automation(true);
        ticks(julia, 40);
        assertFalse(log.contains("record a"));
        assertTrue(status().contains("Julia is not rendering"), status());

        log.clear();
        backend = "metal";
        var baseline = automation(false);
        ticks(baseline, 40);
        assertFalse(log.contains("record a"));
        assertTrue(status().contains("baseline run started Julia"), status());
    }

    @Test
    void aFailedRecordingEndsTheRunWithoutVisitingMore() throws Exception {
        playerReady = true;
        recording = "OVERFLOW";
        var automation = automation(false);
        ticks(automation, 40);
        assertFalse(log.contains("record b"));
        assertFalse(log.contains("quit"));
        assertTrue(status().startsWith("ERROR"), status());
    }

    @Test
    void waitsForTheSquareBeforeRecording() throws Exception {
        playerReady = true;
        arrived = false;
        var automation = automation(false);
        ticks(automation, 5);
        assertFalse(log.contains("record a"));
        assertEquals("POSITIONING a: at 1,2,0", status());
    }

    @Test
    void panelSaysWhereTheRunIs() throws Exception {
        playerReady = true;
        recording = null;
        var automation = automation(false);
        ticks(automation, 40);
        var seconds = Duration.ofSeconds(1).toNanos();
        automation.overlay(clock, 0);
        automation.overlay(clock + seconds, 300);
        var rows = new java.util.HashMap<String, String>();
        for (var row : drawn) rows.put(row.label(), row.value());
        assertEquals("run 1/2 opengl", rows.get("Run"));
        assertEquals("Test GPU", rows.get("GPU"));
        assertEquals("a (1/2)", rows.get("Spot"));
        assertTrue(rows.get("Stage").startsWith("recording"), rows.get("Stage"));
        assertEquals("300 (avg 300)", rows.get("FPS"));
        assertEquals("1 MB", rows.get("Heap"));
        assertEquals("0:01", rows.get("Elapsed"));
        assertEquals("zoom 250%, ZB off, Steam off", rows.get("Args"));
        assertFalse(rows.containsKey("Mods"));
    }

    @Test
    void aSpotItCantReachFailsWithWhereTheCharacterIs() throws Exception {
        playerReady = true;
        arrived = false;
        var automation = automation(false);
        ticks(automation, 70);
        assertTrue(status().startsWith("ERROR"), status());
        assertTrue(status().contains("Can't reach a: at 1,2,0"), status());
        assertTrue(log.stream().filter("arrive a"::equals).count() > 2, "It must retry the teleport");
    }

    @Test
    void screamsOnlyOnTheRecordingPassAndRecordsTheMachine() throws Exception {
        Files.writeString(
                run.resolve("scene.json"),
                "{\"name\":\"s\",\"save\":\"Sandbox/w\",\"spots\":[{\"name\":\"a\",\"scream\":true}]}");
        playerReady = true;
        recording = null;
        var automation = new Automation(
                Scene.read(run.resolve("scene.json")),
                game,
                recorder,
                run.resolve("automation.txt"),
                false,
                () -> backend,
                "x",
                () -> "Test GPU",
                () -> List.of(new Row("Heap", "1 MB")));
        ticks(automation, 7);
        assertEquals(0, screams);
        ticks(automation, 10);
        assertTrue(screams > 0);
        assertEquals(
                "Test GPU | machine",
                Files.readString(run.resolve("machine.txt")).strip());
    }

    @Test
    void requiredModsMustLoadAndFirstPersonIsAskedForOnce() throws Exception {
        var file = run.resolve("mods.json");
        Files.writeString(
                file,
                "{\"name\":\"s\",\"save\":\"Sandbox/w\",\"mods\":[\"Viewpoint\"],\"firstPerson\":true,\"spots\":[{\"name\":\"a\"},{\"name\":\"b\"}]}");
        playerReady = true;
        var missing = new Automation(
                Scene.read(file),
                game,
                recorder,
                run.resolve("automation.txt"),
                false,
                () -> backend,
                "x",
                () -> "g",
                List::of);
        ticks(missing, 5);
        assertTrue(status().contains("Mod didn't load: Viewpoint"), status());

        log.clear();
        loaded = List.of("Viewpoint");
        var present = new Automation(
                Scene.read(file),
                game,
                recorder,
                run.resolve("automation2.txt"),
                false,
                () -> backend,
                "x",
                () -> "g",
                List::of);
        ticks(present, 40);
        assertEquals(1, log.stream().filter("first person"::equals).count());
        assertTrue(log.contains("quit"), log.toString());
    }
}
