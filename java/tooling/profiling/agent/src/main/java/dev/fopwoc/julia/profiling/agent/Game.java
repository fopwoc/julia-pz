package dev.fopwoc.julia.profiling.agent;

import dev.fopwoc.julia.profiling.run.Scene;

/** What the automation needs from the running game, kept apart so its sequencing can be tested without the game. */
interface Game {
    boolean atMainMenu() throws ReflectiveOperationException;

    void loadSave(String mode, String world) throws ReflectiveOperationException;

    boolean playerReady() throws ReflectiveOperationException;

    void arrive(Scene.Spot spot) throws ReflectiveOperationException;

    boolean arrived(Scene.Spot spot) throws ReflectiveOperationException;

    void quit() throws ReflectiveOperationException;
}
