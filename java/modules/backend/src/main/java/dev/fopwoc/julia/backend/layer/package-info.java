/**
 * Back door: overrides of Angelica's SDL backend that change how GL work executes on SDL GPU, one abstract layer per
 * domain. The layers form a single inheritance chain only because the upstream backend is a class; their order carries
 * no meaning.
 */
package dev.fopwoc.julia.backend.layer;
