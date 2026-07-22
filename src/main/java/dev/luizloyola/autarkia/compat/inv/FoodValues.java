package dev.luizloyola.autarkia.compat.inv;

import dev.luizloyola.autarkia.core.inv.ItemStack;
import dev.luizloyola.autarkia.core.person.FoodValue;
import java.util.Optional;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.food.FoodProperties;

/**
 * An item's food payload as the core {@link FoodValue}, read from the item's own {@code FOOD} data
 * component rather than a hardcoded table, so modded foods work too. The mapping is verbatim
 * (checked against the 26.1.2 bytecode) and {@code saturation} is already the precomputed value
 * {@code Needs.eat} expects — no modifier math on this side.
 */
public final class FoodValues {
    private FoodValues() {}

    /**
     * The food payload of {@code stack}'s underlying item, or empty for a non-food (or empty)
     * stack. Registry-aware like {@link ItemStacks#toVanilla}: a component patch can override the
     * item's default {@code FOOD}, so the question is asked of the reconstructed stack.
     */
    public static Optional<FoodValue> of(ItemStack stack, HolderLookup.Provider registries) {
        if (stack.isEmpty()) return Optional.empty();
        net.minecraft.world.item.ItemStack vanilla = ItemStacks.toVanilla(stack, registries);
        FoodProperties food = vanilla.get(DataComponents.FOOD);
        if (food == null) return Optional.empty();
        return Optional.of(new FoodValue(food.nutrition(), food.saturation(), food.canAlwaysEat()));
    }
}
