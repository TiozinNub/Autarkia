package dev.luizloyola.autarkia.core.board;

import dev.luizloyola.anima.core.brain.gate.Gate;
import dev.luizloyola.anima.core.inv.Inventory;
import dev.luizloyola.anima.core.inv.ItemSpec;
import dev.luizloyola.anima.core.inv.ItemStack;
import dev.luizloyola.anima.core.inv.Wear;
import java.util.List;
import java.util.OptionalDouble;
import java.util.Set;
import java.util.function.Predicate;
import org.jspecify.annotations.Nullable;

/**
 * The four tools a settler keeps itself in, and which tier of each its age calls for
 * (docs/superpowers/specs/2026-10-01-tool-up-and-tidy-pack-design.md).
 */
public final class Tools {

    /** In the order the hotbar keeps them. */
    public enum Family {
        SWORD("sword"), PICKAXE("pickaxe"), AXE("axe"), SHOVEL("shovel");

        private final String tool;

        Family(String tool) {
            this.tool = tool;
        }

        public String tool() {
            return tool;
        }

        /** This family's item at {@code tier}, e.g. {@code minecraft:stone_axe}. */
        public String at(String tier) {
            return "minecraft:" + tier + "_" + tool;
        }

        /** Every material a vanilla tool of this kind comes in, golden included. */
        public ItemSpec any() {
            return ItemSpec.anyOf(Set.of(at("wooden"), at("stone"), at("copper"), at("iron"),
                    at("golden"), at("diamond"), at("netherite")));
        }
    }

    /**
     * Worst first. Golden is off the ladder: kept if found, never sought, and it covers nothing,
     * since no age names it and it wears out in a breath.
     */
    public static final List<String> TIERS =
            List.of("wooden", "stone", "copper", "iron", "diamond", "netherite");

    /**
     * Whether an age names the item — Directions' tree, installed by the mod. The gate says yes to
     * anything no node names, so "may make" alone would put every settler on netherite.
     */
    private static volatile Predicate<String> named = id -> false;

    private Tools() {
    }

    public static void install(@Nullable Predicate<String> names) {
        named = names == null ? id -> false : names;
    }

    /** The best tier an age names and this body has reached; wooden when none is named. */
    public static int currentTier(Family family, Gate.View gate) {
        for (int tier = TIERS.size() - 1; tier > 0; tier--) {
            String id = family.at(TIERS.get(tier));
            if (named.test(id) && gate.wouldMake(id)) {
                return tier;
            }
        }
        return 0;
    }

    /** The tier of {@code id} in this family, or -1 for golden and anything else. */
    public static int tierOf(Family family, String id) {
        for (int tier = 0; tier < TIERS.size(); tier++) {
            if (family.at(TIERS.get(tier)).equals(id)) {
                return tier;
            }
        }
        return -1;
    }

    /** A tool of this family at {@code tier} or better that is not worn below {@code spareBelow}. */
    public static boolean covered(Family family, int tier, Inventory pack, double spareBelow) {
        for (int slot = 0; slot < Inventory.ARMOR_START; slot++) {
            ItemStack stack = pack.get(slot);
            if (tierOf(family, stack.id()) >= tier && !worn(stack, spareBelow)) {
                return true;
            }
        }
        return false;
    }

    /** Whether the pack holds any tool of this family that still works, golden and worn included. */
    public static boolean anyHeld(Family family, Inventory pack) {
        return pack.count(family.any().matcher()) > 0;
    }

    public static boolean worn(ItemStack stack, double spareBelow) {
        OptionalDouble left = Wear.left(stack);
        return left.isPresent() && left.getAsDouble() < spareBelow;
    }
}
