/**
 * Controlled scenes written as ordinary LWJGL OpenGL code.
 *
 * <p>The isolated runtime redirects every class in this package as it loads and refuses any class with a remaining
 * native OpenGL call, so these scenes exercise exactly the path a transformed game class takes.
 */
package dev.fopwoc.julia.probe.scene;
