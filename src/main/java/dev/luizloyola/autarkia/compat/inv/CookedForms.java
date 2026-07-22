package dev.luizloyola.autarkia.compat.inv;

import dev.luizloyola.autarkia.core.inv.ItemStack;
import dev.luizloyola.autarkia.core.person.FoodValue;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.item.crafting.AbstractCookingRecipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.item.crafting.SingleRecipeInput;

/**
 * Whether a food is raw and what cooking it yields, read from the recipe data itself: vanilla has
 * no raw-food tag (its food item tags are animal-feed lists), but a beef → cooked beef recipe
 * exists and nothing else does, so modded foods with modded recipes qualify for free.
 *
 * <p><b>The rule</b>: an item has a cooked form iff it has a {@code FOOD} component and a
 * {@code SMELTING}, {@code SMOKING} or {@code CAMPFIRE_COOKING} recipe consumes it whose result is
 * food with STRICTLY higher nutrition — clay is smeltable but not food, and a result no better is
 * not worth a furnace trip. {@code BLASTING} is the ore path and is excluded. Across the three
 * types the highest nutrition wins.
 *
 * <p>Answers per item id, unlike {@link FoodValues#of}: ingredients and default {@code FOOD}
 * components are kind-level data, so the probe is the plain item.
 *
 * <p><b>Cache</b>: this runs inside method-applicability checks on every decomposition, so steady
 * state must be a map hit. Answers (negatives included) are memoized per item id, keyed to the
 * {@link RecipeManager} <em>instance</em>: a {@code /reload} swaps in a new
 * {@code ReloadableServerResources} and so a new manager (verified in the 26.1.2 bytecode), so
 * identity comparison invalidates exactly when the data could have changed. A miss is one linear
 * scan — ~1.5k entries at vanilla scale, once per item kind per reload. Server-thread confined, so
 * no synchronization.
 */
public final class CookedForms {
    private CookedForms() {}

    /** The one-step cooking recipe types food travels through (blasting is the ore path — excluded). */
    private static final Set<RecipeType<?>> COOKING_TYPES =
            Set.of(RecipeType.SMELTING, RecipeType.SMOKING, RecipeType.CAMPFIRE_COOKING);

    /** The {@link RecipeManager} {@link #CACHE} was computed against; a new instance means new data. */
    private static RecipeManager cachedAgainst;
    /** Memoized answers per item id, negatives included (see the class doc's cache rationale). */
    private static final Map<String, Optional<FoodValue>> CACHE = new HashMap<>();

    /**
     * The food value of the strictly-better cooked form of {@code stack}'s item, reachable by one
     * cooking step — or empty when the item is not food, nothing cooks it, or cooking it is no
     * improvement. A map hit after the first ask per item kind.
     */
    public static Optional<FoodValue> of(ItemStack stack, MinecraftServer server) {
        if (stack.isEmpty()) return Optional.empty();
        RecipeManager recipes = server.getRecipeManager();
        if (recipes != cachedAgainst) { // first use, /reload, or a different server — recompute
            CACHE.clear();
            cachedAgainst = recipes;
        }
        return CACHE.computeIfAbsent(stack.id(), id -> scan(id, recipes, server.registryAccess()));
    }

    /** The uncached answer for item kind {@code id}: one pass over the recipe collection. */
    private static Optional<FoodValue> scan(String id, RecipeManager recipes, HolderLookup.Provider registries) {
        // Probe with the PLAIN item (no component patch): the cache is per kind, so the answer must
        // not depend on which stack instance happened to ask first.
        net.minecraft.world.item.ItemStack raw = ItemStacks.toVanilla(ItemStack.of(id, 1, 1), registries);
        if (raw.isEmpty()) return Optional.empty(); // unknown id
        FoodProperties rawFood = raw.get(DataComponents.FOOD);
        if (rawFood == null) return Optional.empty(); // not food — smeltable non-foods (clay) end here

        FoodValue best = null;
        for (RecipeHolder<?> holder : recipes.getRecipes()) {
            // instanceof besides the type check: a modded recipe could register under a cooking
            // type with an exotic class; only AbstractCookingRecipe guarantees input()/assemble().
            if (!(holder.value() instanceof AbstractCookingRecipe cooking)) continue;
            if (!COOKING_TYPES.contains(cooking.getType())) continue;
            if (!cooking.input().test(raw)) continue;
            net.minecraft.world.item.ItemStack cooked = cooking.assemble(new SingleRecipeInput(raw));
            FoodProperties cookedFood = cooked.get(DataComponents.FOOD);
            if (cookedFood == null || cookedFood.nutrition() <= rawFood.nutrition()) continue;
            if (best == null || cookedFood.nutrition() > best.nutrition()) {
                best = new FoodValue(cookedFood.nutrition(), cookedFood.saturation(), cookedFood.canAlwaysEat());
            }
        }
        return Optional.ofNullable(best);
    }
}
