package dev.fopwoc.julia.profiling.agent;

import dev.fopwoc.julia.profiling.run.Scene;
import java.util.List;

/** What the automation needs from the running game, kept apart so its sequencing can be tested without the game. */
interface Game {
    boolean atMainMenu() throws ReflectiveOperationException;

    void loadSave(String mode, String world) throws ReflectiveOperationException;

    boolean playerReady() throws ReflectiveOperationException;

    void arrive(Scene.Spot spot) throws ReflectiveOperationException;

    boolean arrived(Scene.Spot spot) throws ReflectiveOperationException;

    /** The mods the game actually loaded, which is what a report should list. */
    List<String> loadedMods() throws ReflectiveOperationException;

    /** One step toward Viewpoint's first person, {@code true} once there. Called again a second later until then. */
    boolean firstPerson() throws ReflectiveOperationException;

    /** A loud world sound at the character, which draws zombies from a wide area. */
    void scream() throws ReflectiveOperationException;

    /** A description of the machine and display, for the report. */
    String machine() throws ReflectiveOperationException;

    /** Where the character really is, for reports when a spot can't be reached. */
    String position() throws ReflectiveOperationException;

    /** The zoom level as a percentage, after moving it to the widest the game allows, which draws the most. */
    int zoomOut() throws ReflectiveOperationException;

    /** Points the character somewhere, so what's around them is seen and not left behind fog. */
    void face(float degrees) throws ReflectiveOperationException;

    /** Reapplied for the whole run, because something in the game can clear it again after it's set. */
    void keepAlive() throws ReflectiveOperationException;

    void drawPanel(List<Row> rows) throws ReflectiveOperationException;

    void quit() throws ReflectiveOperationException;
}
