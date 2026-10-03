package dev.luizloyola.autarkia.core.patch;

import dev.luizloyola.anima.core.brain.knowledge.BlockKind;
import dev.luizloyola.anima.core.brain.knowledge.PoiKind;
import dev.luizloyola.anima.core.inv.ItemSpec;

/**
 * A plant that grows up in a column — cane, cactus, bamboo — and how it is cut ({@link CutStalks}).
 *
 * @param patch      where it is remembered
 * @param block      what it is in the world
 * @param item       what cutting it gives
 * @param keepBottom cut above the bottom block, which grows the column again
 * @param harmful    it hurts to touch: cut it, and pick up what falls, from a cell beside nothing of it
 */
public record Stalk(PoiKind patch, BlockKind block, ItemSpec item, boolean keepBottom, boolean harmful) {
}
