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
                "{\"name\":\"s\",\"save\":\"Sandbox/w\",\"spots\":[{\"name\":\"a\"},{\"name\":\"b\",\"ghost\":true}]}");
        return Scene.read(file);
    }

    private Automation automation(boolean julia) throws IOException {
        return new Automation(scene(), game, recorder, run.resolve("automation.txt"), julia, () -> backend);
    }

    private void ticks(Automation automation, int count) {
        for (int at = 0; at < count; at++)
            automation.tick(at * Duration.ofSeconds(1).toNanos());
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
        ticks(automation, 10);
        assertEquals(List.of("load Sandbox/w", "arrive a", "record a", "arrive b", "record b", "quit"), log);
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
        ticks(julia, 5);
        assertFalse(log.contains("record a"));
        assertTrue(status().contains("Julia is not rendering"), status());

        log.clear();
        backend = "metal";
        var baseline = automation(false);
        ticks(baseline, 5);
        assertFalse(log.contains("record a"));
        assertTrue(status().contains("baseline run started Julia"), status());
    }

    @Test
    void aFailedRecordingEndsTheRunWithoutVisitingMore() throws Exception {
        playerReady = true;
        recording = "OVERFLOW";
        var automation = automation(false);
        ticks(automation, 8);
        assertFalse(log.contains("arrive b"));
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
        assertEquals("POSITIONING a", status());
    }
}
