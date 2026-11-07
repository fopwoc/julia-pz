package dev.fopwoc.julia.profiling.agent;

import dev.fopwoc.julia.profiling.run.Scene;

/** What the automation needs from the running game, kept apart so its sequencing can be tested without the game. */
interface Game {
    boolean atMainMenu() throws ReflectiveOperationException;

    void loadSave(String mode, String world) throws ReflectiveOperationException;

    boolean playerReady() throws ReflectiveOperationException;

    void arrive(Scene.Spot spot) throws ReflectiveOperationException;

    boolean arrived(Scene.Spot spot) throws ReflectiveOperationException;

    /** Points the character somewhere, so what's around them is seen and not left behind fog. */
    void face(float degrees) throws ReflectiveOperationException;

    void drawText(String text) throws ReflectiveOperationException;

    void quit() throws ReflectiveOperationException;
}
