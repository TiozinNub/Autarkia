package dev.luizloyola.autarkia.compat.sense;

import dev.luizloyola.anima.compat.sense.BlockKinds;
import dev.luizloyola.anima.core.brain.knowledge.BlockKind;
import dev.luizloyola.autarkia.core.patch.Patches;
import java.util.Optional;
import net.minecraft.world.level.block.Blocks;

/**
 * How a settler tells a pumpkin from the rest of the world — the recognising half of
 * {@link Patches}, and the first thing through Anima's block-kind seam, whose own ladder would call
 * every one of these {@link BlockKind#OTHER}.
 *
 * <p><b>By block, not by tag.</b> Other classifications ride tags (logs, leaves, water), because
 * those are open sets a modpack extends. These are not: {@code minecraft:pumpkin} is one block, and
 * there is no vanilla tag groups the gourds; a pack with its own crop registers its own classifier.
 *
 * <p>Carved pumpkins and jack-o'-lanterns count: a body walking past cannot tell them apart at any
 * distance worth remembering, and both mean pumpkins grow here.
 */
public final class PatchBlocks {

    private PatchBlocks() {
    }

    /** Call during mod initialization. */
    public static void register() {
        BlockKinds.register((level, pos, state) -> {
            if (state.is(Blocks.PUMPKIN) || state.is(Blocks.CARVED_PUMPKIN)
                    || state.is(Blocks.JACK_O_LANTERN)) {
                return Optional.of(Patches.PUMPKIN);
            }
            if (state.is(Blocks.MELON)) {
                return Optional.of(Patches.MELON);
            }
            if (state.is(Blocks.CACTUS)) {
                return Optional.of(Patches.CACTUS);
            }
            // The two with no collision at all. Anima's floor would call these AIR — the rung that
            // stops a meadow reading as a field of things — so consumers must be asked before it.
            if (state.is(Blocks.SUGAR_CANE)) {
                return Optional.of(Patches.SUGAR_CANE);
            }
            if (state.is(Blocks.SWEET_BERRY_BUSH)) {
                return Optional.of(Patches.SWEET_BERRIES);
            }
            return Optional.empty();
        });
    }
}
