package dev.luizloyola.autarkia.core.bp;

import dev.luizloyola.autarkia.core.bp.Blueprint.Outcome;
import dev.luizloyola.autarkia.core.bp.Dictionary.BlockInfo;

/**
 * Whether a block's face holds what hangs on it. Vanilla decides from the block's shape — a fence
 * post holds a torch on top and not on its side — through {@code isFaceSturdy}, which core cannot
 * call, so the game passes it in. The tests and the offline checker use {@link #solidCubes}.
 */
public interface Support {

    /** The first four in {@link Blueprint.Facing}'s order. */
    enum Face { NORTH, EAST, SOUTH, WEST, UP, DOWN }

    /**
     * @param face   the face of {@code block} turned toward what hangs on it
     * @param center only the middle of the face is needed, as a torch standing on it or a lantern
     *               hanging under it needs; a wall torch or a button needs the whole face
     */
    boolean holds(Outcome block, Face face, boolean center);

    /** A full solid cube holds anything on every face, and nothing else holds at all. */
    static Support solidCubes(Dictionary dict) {
        return (block, face, center) -> dict.block(block.block()).map(BlockInfo::solid).orElse(false);
    }
}
