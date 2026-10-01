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
 * own slot beats it one slot over, an older one sits past the four, any tool on the hotbar beats
 * the backpack, and anything that is not a tool is better off in the backpack.
 */
public final class SettlerPack implements PackLayout {

    public static final SettlerPack INSTANCE = new SettlerPack();

    /** The newest tool of a family in its own slot, less one per slot away from it. */
    static final double HOME = 10.0;
    /** An older tool of a family, or a golden one, past the four. */
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
        boolean newest = family != null && isNewest(family, stack, pack);
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

    /** Whether no tool of the family in the pack has a better tier; golden never is. */
    private static boolean isNewest(Tools.Family family, ItemStack stack, Inventory pack) {
        int tier = Tools.tierOf(family, stack.id());
        if (tier < 0) {
            return false;
        }
        for (int slot = 0; slot < Inventory.ARMOR_START; slot++) {
            if (Tools.tierOf(family, pack.get(slot).id()) > tier) {
                return false;
            }
        }
        return true;
    }
}
