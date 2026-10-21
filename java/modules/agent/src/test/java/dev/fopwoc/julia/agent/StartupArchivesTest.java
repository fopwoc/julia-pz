package dev.fopwoc.julia.agent;

import static org.junit.jupiter.api.Assertions.*;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class StartupArchivesTest {
    @Test
    void addsLaterAgentJarsThatAreNotOnTheClassPathYet(@TempDir Path game) throws Exception {
        var pz = Files.createFile(game.resolve("projectzomboid.jar"));
        var julia = Files.createFile(game.resolve("julia-agent.jar"));
        var buddy = Files.createFile(game.resolve("ZombieBuddy.jar"));
        Files.createDirectory(game.resolve("classes"));

        var archives = StartupArchives.collect(
                String.join(File.pathSeparator, "projectzomboid.jar", "classes", "julia-agent.jar"),
                List.of(
                        "-Xmx3072m",
                        "-javaagent:julia-agent.jar=activate",
                        "-javaagent:ZombieBuddy.jar=verbosity=1",
                        "-javaagent:" + game.resolve("ZombieBuddy.jar")),
                julia,
                game);

        assertEquals(List.of(pz, buddy), archives);
    }
}
