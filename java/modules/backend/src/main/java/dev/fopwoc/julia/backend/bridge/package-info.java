/**
 * Front door: static GL entry points that rewritten game and mod bytecode calls directly, for overloads and extensions
 * Angelica's redirector does not map. Bridges adapt the call and forward inward; every bridge class is listed in
 * {@code Endpoint}.
 */
package dev.fopwoc.julia.backend.bridge;
