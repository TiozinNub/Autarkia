package dev.luizloyola.autarkia.core.board;

import dev.luizloyola.anima.core.brain.task.Food;
import dev.luizloyola.anima.core.inv.ItemCall;
import dev.luizloyola.anima.core.inv.ItemSpec;
import dev.luizloyola.anima.core.inv.Kit;

/**
 * The item kinds Autarkia's settlers care about holding — here rather than in Anima because which
 * items matter is a question about the world being modelled, not about having a mind.
 *
 * <p>These constants are the keys {@code Producers} is registered against, so everything that wants
 * logs must want <em>this</em> LOGS.
 */
public final class Stock {

    /** Wood in log form — every overworld {@code *_log} plus the nether {@code *_stem}s.
     *  String-level vanilla knowledge, the same convention the chop's sapling map uses;
     *  provisional until a compat tag lens ({@code ItemTags.LOGS}) replaces the predicate. */
    public static final ItemSpec LOGS =
            ItemSpec.register(
                    new ItemSpec("logs", id -> id.endsWith("_log") || id.endsWith("_stem")));

    /** Planks of any wood — what a furnace burns: a log as four planks smelts six, burnt whole one
     *  and a half. The string-level convention of {@link #LOGS}. */
    public static final ItemSpec PLANKS =
            ItemSpec.register(new ItemSpec("planks", id -> id.endsWith("_planks")));

    /**
     * What a furnace burns before planks: vanilla fuels a clearing sweeps up and nothing else uses
     * yet. Saplings are left out — they are for planting once anybody plants.
     */
    public static final ItemSpec KINDLING = ItemSpec.register(new ItemSpec("kindling", id ->
            id.equals("minecraft:leaf_litter") || id.equals("minecraft:dead_bush")
                    || id.equals("minecraft:short_dry_grass") || id.equals("minecraft:tall_dry_grass")));

    /** Any pickaxe, any tier — what mining stone needs, since stone mined bare-handed drops nothing. */
    public static final ItemSpec PICKAXES =
            ItemSpec.register(new ItemSpec("pickaxes", id -> id.endsWith("_pickaxe")));

    /**
     * What a furnace is crafted from — vanilla's {@code #minecraft:stone_crafting_materials}, which
     * only the mod layer can read, so it installs the rule ({@link #furnaceStoneBy}).
     */
    public static final ItemSpec FURNACE_STONE =
            ItemSpec.register(new ItemSpec("furnace_stone", id -> Stock.furnaceStone.test(id)));

    private static volatile java.util.function.Predicate<String> furnaceStone = id -> false;

    /** Sets what {@link #FURNACE_STONE} matches. */
    public static void furnaceStoneBy(java.util.function.Predicate<String> rule) {
        furnaceStone = rule;
    }

    /** Any axe, any tier — what chopping WANTS (never needs: a chop works bare-handed, slower).
     *  Same string-level convention as {@link #LOGS}; the wield step never reads this — it
     *  measures — so the spec only has to be right where there is no block to measure against. */
    public static final ItemSpec AXES =
            ItemSpec.register(new ItemSpec("axes", id -> id.endsWith("_axe")));

    /** Any sword, any tier — what a hunt WANTS; a fight works bare-handed, slower. */
    public static final ItemSpec SWORDS =
            ItemSpec.register(new ItemSpec("swords", id -> id.endsWith("_sword")));

    /**
     * The tools a trip for {@code spec} could use (Luiz, 2026-10-01: work that a tool helps wants
     * it). Logs come by chopping and food partly by hunting; anything else is fetched by hand.
     */
    public static Kit gatheringKit(ItemSpec spec) {
        if (spec.name().equals(LOGS.name())) {
            return CHOPPING;
        }
        if (spec.name().equals(Food.SPEC.name())) {
            return HUNTING;
        }
        return Kit.NONE;
    }

    private static final Kit CHOPPING = Kit.of(ItemCall.want(AXES, 1));
    private static final Kit HUNTING = Kit.of(ItemCall.want(SWORDS, 1));

    /**
     * Blocks a walk may lay to bridge or pillar — Anima's {@code #anima:bridging_blocks}, which only
     * the mod layer can read, so it installs the rule ({@link #layableBy}). A settler keeps a stack
     * of them ({@link StandingWants}).
     */
    public static final ItemSpec BRIDGING =
            ItemSpec.register(new ItemSpec("bridging_blocks", id -> Stock.layable.test(id)));

    private static volatile java.util.function.Predicate<String> layable = id -> false;

    /** Sets what {@link #BRIDGING} matches. */
    public static void layableBy(java.util.function.Predicate<String> rule) {
        layable = rule;
    }

    /** Any shovel, any tier — what digging dirt WANTS; it works bare-handed, slower. */
    public static final ItemSpec SHOVELS =
            ItemSpec.register(new ItemSpec("shovels", id -> id.endsWith("_shovel")));

    /**
     * Vanilla's {@code #minecraft:dirt} as items — what tops a filled column so it reads as natural
     * ground again. Only the mod layer can read the tag, so it installs the rule ({@link #dirtBy}).
     * Not named {@code dirt}: that is the name {@code ItemSpec.anyOf} gives the dirt item alone, and
     * whichever registered first took the other's place.
     */
    public static final ItemSpec DIRT =
            ItemSpec.register(new ItemSpec("any_dirt", id -> Stock.dirt.test(id)));

    private static volatile java.util.function.Predicate<String> dirt = id -> false;

    /** Sets what {@link #DIRT} matches. */
    public static void dirtBy(java.util.function.Predicate<String> rule) {
        dirt = rule;
    }

    private Stock() {
    }
}
