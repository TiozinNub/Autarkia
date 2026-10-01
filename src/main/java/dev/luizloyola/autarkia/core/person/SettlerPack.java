package dev.luizloyola.autarkia.core.person;

import dev.luizloyola.anima.core.inv.Inventory;
import dev.luizloyola.anima.core.inv.ItemStack;
import dev.luizloyola.anima.core.inv.PackLayout;
import dev.luizloyola.anima.core.inv.Wear;
import dev.luizloyola.autarkia.core.board.Tools;

/**
 * Where a settler keeps things (Luiz, 2026-10-01): tools toward the hotbar, the four in the order
 * sword, pickaxe, axe, shovel, everything else in the backpack, and one hotbar slot free.
 *
 * <p>The numbers are a first guess. What matters is their order: the newest tool of a family in its
 * own slot beats it one slot over, an older one or a spare sits past the four, any tool on the hotbar beats
 * the backpack, and anything that is not a tool is better off in the backpack.
 */
public final class SettlerPack implements PackLayout {

    public static final SettlerPack INSTANCE = new SettlerPack();

    /** The newest tool of a family in its own slot, less one per slot away from it. */
    static final double HOME = 10.0;
    /** An older tool of a family, a spare, or a golden one, past the four. */
    static final double OLD = 6.0;
    /** Any other tool, anywhere on the hotbar. */
    static final double TOOL = 3.0;
    /** Anything else on the hotbar. */
    static final double CLUTTER = -5.0;

    private SettlerPack() {
    }

    @Override
    public double[] weights(ItemStack stack, Inventory pack) {
        double[] fit = new double[Inventory.ARMOR_START];
        Tools.Family family = familyOf(stack.id());
        boolean newest = family != null && isHome(family, stack, pack);
        boolean tool = family != null || Wear.wears(stack);
        for (int slot = Inventory.HOTBAR_START; slot < Inventory.MAIN_START; slot++) {
            int fours = Tools.Family.values().length;
            fit[slot] = newest ? Math.max(TOOL + 1, HOME - Math.abs(slot - family.ordinal()))
                    : family != null ? (slot < fours ? OLD - 2 : OLD)
                    : tool ? TOOL : CLUTTER;
        }
        return fit;
    }

    @Override
    public int freeHotbar() {
        return 1;
    }

    private static Tools.@org.jspecify.annotations.Nullable Family familyOf(String id) {
        for (Tools.Family family : Tools.Family.values()) {
            if (family.any().matches(id)) {
                return family;
            }
        }
        return null;
    }

    /**
     * Whether this is the one tool of its family that sits at home: the best tier in the pack, and
     * of two at that tier the one in the lower slot. A stack not yet in the pack (a pickup) loses
     * that tie, so a second sword lands as a spare. Golden never is.
     */
    private static boolean isHome(Tools.Family family, ItemStack stack, Inventory pack) {
        int tier = Tools.tierOf(family, stack.id());
        if (tier < 0) {
            return false;
        }
        int at = Integer.MAX_VALUE;
        for (int slot = 0; slot < Inventory.ARMOR_START; slot++) {
            if (pack.get(slot) == stack) {
                at = slot;
            }
        }
        for (int slot = 0; slot < Inventory.ARMOR_START; slot++) {
            int other = Tools.tierOf(family, pack.get(slot).id());
            if (other > tier || other == tier && slot < at) {
                return false;
            }
        }
        return true;
    }
}
