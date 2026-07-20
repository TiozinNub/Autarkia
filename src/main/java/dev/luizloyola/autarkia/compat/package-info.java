/**
 * Version-compatibility layer: the only home of version-specific code.
 *
 * <p>This package defines a version-neutral facade (world queries, entity actions, block
 * placement, pathfinding, inventories…) named for what NPCs need — not for Minecraft
 * internals. {@link dev.luizloyola.autarkia.core} and {@link dev.luizloyola.autarkia.mod}
 * consume these interfaces; every Minecraft version difference is absorbed here.
 *
 * <p>Stonecutter preprocessor comments ({@code //? if …}) are allowed only in this package
 * (and in mixins, which are part of the compat surface).
 */
package dev.luizloyola.autarkia.compat;
