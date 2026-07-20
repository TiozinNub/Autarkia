/**
 * Pure simulation layer: AI, planning, data structures, settlement state, relationships.
 *
 * <p>Rules for this package and everything below it:
 * <ul>
 *   <li>No {@code net.minecraft} or {@code net.fabricmc} imports — ever.</li>
 *   <li>No Stonecutter preprocessor comments.</li>
 *   <li>Java 17 language level while 1.20.1 is a supported target.</li>
 *   <li>Everything here must be unit-testable with plain JUnit, no Minecraft instance.</li>
 * </ul>
 *
 * <p>Interaction with the game world goes exclusively through the facade interfaces
 * defined in {@link dev.luizloyola.autarkia.compat}.
 */
package dev.luizloyola.autarkia.core;
