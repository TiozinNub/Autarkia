package dev.luizloyola.autarkia.core.brain.task;

import dev.luizloyola.autarkia.core.brain.BrainContext;
import dev.luizloyola.autarkia.core.brain.sense.FoodLookup;
import dev.luizloyola.autarkia.core.inv.Inventory;
import dev.luizloyola.autarkia.core.person.FoodValue;
import dev.luizloyola.autarkia.core.person.Needs;
import java.util.Optional;

/**
 * The shared bite-selection policy behind {@link EatReadyFood} and {@link EatLastResort}. Stateless:
 * fresh percepts every expansion.
 *
 * <p><b>The two tiers</b> (a carried food is in exactly one):
 * <ul>
 *   <li><b>ready</b> — no strictly-better cooked form and not a {@code canAlwaysEat} treat; free to
 *       eat now ({@link EatReadyFood}).</li>
 *   <li><b>last resort</b> — a better cooked form OR a treat; priced by {@link EatLastResort}.</li>
 * </ul>
 *
 * <p><b>Ordering</b>: least {@code waste}, then highest {@code nutrition}, then lowest slot, where
 * {@code missing = MAX_FOOD - foodLevel} and {@code waste = max(0, nutrition - missing)} — at food
 * 18 a 1-point potato beats an 8-point steak (6 spilled). Lowest slot falls out of strictly-better
 * comparison over {@link Inventory#occupied()}'s ascending iteration.
 */
final class EatSelection {

    private EatSelection() {
    }

    /** The best ready-tier entry to eat, or empty when there is none (includes {@code missing == 0}). */
    static Optional<Inventory.Entry> bestReady(BrainContext ctx) {
        return best(ctx, false);
    }

    /** The best last-resort-tier entry to eat, or empty when there is none (includes {@code missing == 0}). */
    static Optional<Inventory.Entry> bestLastResort(BrainContext ctx) {
        return best(ctx, true);
    }

    /**
     * Whether {@code stack} is last-resort food. Assumes it is already known edible; callers gate on
     * {@link FoodLookup#of} first.
     */
    static boolean isLastResort(FoodLookup foods, FoodValue food, dev.luizloyola.autarkia.core.inv.ItemStack stack) {
        return food.canAlwaysEat() || foods.cookedForm(stack).isPresent();
    }

    private static Optional<Inventory.Entry> best(BrainContext ctx, boolean wantLastResort) {
        Needs needs = ctx.percepts().needs();
        int missing = Needs.MAX_FOOD - needs.foodLevel();
        if (missing == 0) {
            return Optional.empty(); // nothing to restore — no reason to eat anything, either tier
        }
        FoodLookup foods = ctx.percepts().foods();
        Inventory.Entry bestEntry = null;
        Candidate best = null;
        for (Inventory.Entry entry : ctx.percepts().inventory().occupied()) {
            Optional<FoodValue> value = foods.of(entry.stack());
            if (value.isEmpty()) {
                continue;
            }
            FoodValue food = value.get();
            if (isLastResort(foods, food, entry.stack()) != wantLastResort) {
                continue; 
            }
            Candidate candidate =
                    new Candidate(Math.max(0, food.nutrition() - missing), food.nutrition());
            if (candidate.beats(best)) {
                best = candidate;
                bestEntry = entry;
            }
        }
        return Optional.ofNullable(bestEntry);
    }

    /** A tier's running best; strictly-better wins, so ascending-slot iteration keeps the lowest index on full ties. */
    private record Candidate(int waste, int nutrition) {
        boolean beats(Candidate incumbent) {
            if (incumbent == null) {
                return true;
            }
            if (waste != incumbent.waste) {
                return waste < incumbent.waste;
            }
            return nutrition > incumbent.nutrition;
        }
    }
}
