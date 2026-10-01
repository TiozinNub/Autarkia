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
 * <p>The numbers are a first guess. What matters is their order: a family tool in its own slot
 * beats it one slot over, any tool on the hotbar beats the backpack, and anything that is not a
 * tool is better off in the backpack.
 */
public final class SettlerPack implements PackLayout {

    public static final SettlerPack INSTANCE = new SettlerPack();

    /** A family tool in its own slot, less one per slot away from it. */
    static final double HOME = 10.0;
    /** Any other tool, anywhere on the hotbar. */
    static final double TOOL = 3.0;
    /** Anything else on the hotbar. */
    static final double CLUTTER = -5.0;

    private SettlerPack() {
    }

    @Override
    public double[] weights(ItemStack stack) {
        double[] fit = new double[Inventory.ARMOR_START];
        int home = homeOf(stack.id());
        boolean tool = home >= 0 || Wear.wears(stack);
        for (int slot = Inventory.HOTBAR_START; slot < Inventory.MAIN_START; slot++) {
            fit[slot] = home >= 0 ? Math.max(TOOL + 1, HOME - Math.abs(slot - home))
                    : tool ? TOOL : CLUTTER;
        }
        return fit;
    }

    @Override
    public int freeHotbar() {
        return 1;
    }

    /** The hotbar slot a family tool belongs in, or -1. */
    private static int homeOf(String id) {
        for (Tools.Family family : Tools.Family.values()) {
            if (family.any().matches(id)) {
                return family.ordinal();
            }
        }
        return -1;
    }
}
