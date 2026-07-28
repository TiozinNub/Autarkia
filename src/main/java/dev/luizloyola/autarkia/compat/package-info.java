/**
 * Version-compatibility layer: the only home of version-specific code in Autarkia.
 *
 * <p>Most of the facade now lives in {@link dev.luizloyola.anima.compat} — world queries, block
 * probing, inventories, saved data, gizmos — because none of it needed to know it was serving a
 * Person. What stays here is what does: presentation for the Person entity, starting with its
 * inventory screen.
 *
 * <p>Stonecutter preprocessor comments ({@code //? if …}) are allowed only in this package (and
 * in mixins, which are part of the compat surface).
 */
package dev.luizloyola.autarkia.compat;
