package dev.luizloyola.autarkia.core.patch;

import dev.luizloyola.anima.core.brain.knowledge.BlockKind;
import dev.luizloyola.anima.core.brain.knowledge.PoiKind;

/**
 * The things that grow in scattered clumps on the ground, and are worth walking to.
 *
 * <p>Not masses like trees and water. That is what the region flood is built for: a pumpkin patch
 * is up to sixty-four worldgen tries inside a fifteen-block square, so no two blocks necessarily
 * touch and a flood from one of them finds one of them.
 *
 * <p>So: <b>one memory per patch, arrived at by merging</b> under a wide radius, the store
 * collapsing clumps onto the first, which puts the anchor at the near edge.
 *
 * <p>None of these counts anything: the merge REPLACES inside the radius rather than accumulating,
 * so a count would be the size of the last clump seen (one, usually) passed off as the patch's.
 * An accurate count needs a scan that sees the whole scatter at once.
 */
public final class Patches {

    /**
     * A pumpkin block — the grown kind and the carved kind alike, since a settler walking past
     * cannot tell a jack-o'-lantern from a pumpkin and neither can this.
     */
    public static final BlockKind PUMPKIN = BlockKind.register("pumpkin");

    public static final BlockKind MELON = BlockKind.register("melon");

    /** A cactus. Not food, but it stands where nothing else does. */
    public static final BlockKind CACTUS = BlockKind.register("cactus");

    /**
     * A sugar cane stalk. Unlike the three above it has no collision at all, so it is absent from
     * the motion-blocking heightmap and a body asking what a column's SURFACE was got the sand
     * underneath a whole cane brake. That is what {@code BlockProbe.topY} exists to answer, and
     * this is the first kind that needs it.
     */
    public static final BlockKind SUGAR_CANE = BlockKind.register("sugar_cane");

    /** A sweet berry bush, collision-free for the same reason and found the same way. */
    public static final BlockKind SWEET_BERRIES = BlockKind.register("sweet_berries");

    /**
     * Somewhere pumpkins grow. Merge radius 12 — wide enough that one worldgen patch is one
     * memory however its blocks are strewn (the spread is 7 either way from a centre, so two
     * blocks of the same patch are at most 14 apart, and a clump met from either end lands
     * inside this of the other).
     */
    public static final PoiKind PUMPKINS = PoiKind.register("pumpkins", 12, "");

    public static final PoiKind MELONS = PoiKind.register("melons", 12, "");

    /**
     * Somewhere cacti grow. Same radius for the same reason, though a desert's cacti are strewn
     * wider — worst case two memories for one stretch of desert, which is not wrong.
     */
    public static final PoiKind CACTI = PoiKind.register("cacti", 12, "");

    /**
     * A cane brake. Merge radius 8 rather than 12: cane grows in clumps along a water's edge, and
     * at 12 a body would collapse two banks of a narrow river into one belief anchored in the water
     * between them.
     *
     * <p>{@link PoiKind.Settling#SURFACE} only because a glance looks at what STANDS on a column; a
     * body asking only for the column's surface could never settle cane, leaving a rumour nothing
     * can confirm.
     */
    public static final PoiKind CANE = PoiKind.register("cane", 8, "");

    /** A stand of berry bushes. Same clumping, same reach. */
    public static final PoiKind BERRIES = PoiKind.register("berries", 8, "");

    private Patches() {
    }

    /** Touching this class registers the kinds. Called from the mod initializer. */
    public static void init() {
    }
}
