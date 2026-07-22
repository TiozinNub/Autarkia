package dev.luizloyola.autarkia.core.brain.sense;

import dev.luizloyola.autarkia.core.inv.ItemStack;
import dev.luizloyola.autarkia.core.person.FoodValue;
import java.util.Optional;

/**
 * The food sense: what eating a stack would do. Implemented by {@code compat} over the item
 * registry (the real item's {@code FoodProperties} read into a {@link FoodValue}) — food values
 * come from vanilla, not a hardcoded table, so modded foods work for free.
 */
public interface FoodLookup {
    /**
     * The food value of {@code stack}'s item, or empty when it is not edible (or the stack is
     * empty). Cheap enough to call while scoring methods — a registry component read, no world
     * access.
     */
    Optional<FoodValue> of(ItemStack stack);

    /**
     * The food value of the strictly-better cooked form reachable by one cooking step (smelting,
     * smoking, or campfire), or empty when the item isn't food, isn't cookable, or cooking doesn't
     * improve its nutrition. Supplied by {@code compat} from live recipe data: vanilla has no
     * raw/cooked tags, so an item is "raw" exactly when a cooking recipe makes it better, and core
     * never grows a curated table.
     */
    Optional<FoodValue> cookedForm(ItemStack stack);
}
