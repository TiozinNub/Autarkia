package dev.luizloyola.autarkia.compat.sense;

import dev.luizloyola.anima.compat.sense.BlockKinds;
import dev.luizloyola.autarkia.core.patch.Landmarks;
import java.util.Optional;
import net.minecraft.tags.BlockTags;

/**
 * How a settler tells stone and bee nests from the rest of the world — the recognising half of
 * {@link Landmarks}. By vanilla tag, so a modpack's stone and hives are seen too.
 */
public final class LandmarkBlocks {

    private LandmarkBlocks() {
    }

    /** Call during mod initialization. */
    public static void register() {
        BlockKinds.register((level, pos, state) -> {
            if (state.is(BlockTags.BASE_STONE_OVERWORLD)) {
                return Optional.of(Landmarks.STONE);
            }
            if (state.is(BlockTags.BEEHIVES)) {
                return Optional.of(Landmarks.BEE_NEST);
            }
            return Optional.empty();
        });
    }
}
