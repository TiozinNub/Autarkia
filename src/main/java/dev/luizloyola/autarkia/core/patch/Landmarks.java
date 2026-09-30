package dev.luizloyola.autarkia.core.patch;

import dev.luizloyola.anima.core.brain.knowledge.BlockKind;
import dev.luizloyola.anima.core.brain.knowledge.PoiKind;

/**
 * What makes a place worth living in and lasts, as a settler sees it: exposed stone and bee nests
 * (docs/superpowers/specs/2026-09-25-home-search-design.md). Remembered for the HOME search, which
 * judges a place by what its settler has seen.
 *
 * <p>Both block kinds are solid ground: to the tree code a trunk on stone still stands on ground.
 *
 * <p>Neither kind is settled by a glance at a column's top. The far sense sights a stone cliff by
 * its face and a nest on a trunk's side, and the column top there is grass or leaves; a glance that
 * settled them would delete a true belief that the next sweep sights again.
 */
public final class Landmarks {

    /** Overworld stone, by {@code #minecraft:base_stone_overworld}: granite and tuff count. */
    public static final BlockKind STONE = BlockKind.registerGround("stone");

    /** A bee nest or a beehive, by {@code #minecraft:beehives}. */
    public static final BlockKind BEE_NEST = BlockKind.registerGround("bee_nest");

    /**
     * Stone showing at the surface. Merge radius 12, one memory per outcrop-sized stretch of a
     * mountain: the search asks how near the nearest is, never how many.
     */
    public static final PoiKind STONE_POI =
            PoiKind.register("stone", 12, " blocks", PoiKind.Settling.DELIBERATE);

    /** A bee nest or hive. */
    public static final PoiKind BEES =
            PoiKind.register("bee", 8, " nests", PoiKind.Settling.DELIBERATE);

    private Landmarks() {
    }

    /** Touching this class registers the kinds. Called from the mod initializer. */
    public static void init() {
    }
}
