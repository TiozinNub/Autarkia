package dev.luizloyola.autarkia.core.brain.task;

import dev.luizloyola.autarkia.core.brain.BrainContext;
import dev.luizloyola.autarkia.core.brain.sense.FoodLookup;
import dev.luizloyola.autarkia.core.inv.Inventory;
import dev.luizloyola.autarkia.core.person.FoodValue;
import dev.luizloyola.autarkia.core.person.Needs;
import java.util.List;
import java.util.Optional;

/**
 * The first {@link Method}: eat something she already carries. Decomposes to a single
 * {@link ConsumeItem} on the slot the policy picks (equipment slots included — offhand food is how
 * players carry a snack); applicable exactly when the policy picks one. {@link #estimateCost} is
 * {@code 0.0} (already carried) the baseline every acquiring method prices itself against.
 *
 * <p><b>Slot policy — two tiers, least waste within each.</b> With
 * {@code missing = MAX_FOOD - foodLevel} and {@code waste = max(0, nutrition - missing)}:
 * <ul>
 *   <li><b>Tier 1 — plain ready food</b> (no better cooked form per {@link FoodLookup#cookedForm},
 *       not a {@code canAlwaysEat} treat): least waste, then highest nutrition, then lowest slot —
 *       so at food 18 she eats the potato (wastes nothing), not the steak (8 points, 6 off the top
 *       of the bar).</li>
 *   <li><b>Tier 2 — last resort</b>, only when tier 1 is empty and {@code band() == STARVING}:
 *       raw-but-cookable food and {@code canAlwaysEat} treats, same ordering. Raw-aversion is
 *       OPPORTUNITY COST — a raw potato (1) forgoes the baked form (5) — and golden apples are
 *       emergency food.</li>
 * </ul>
 * When neither tier yields a slot, {@link #applicable} is false and {@link SatisfyHunger} reports
 * no acceptable way to eat.
 */
public final class EatFromInventory implements Method {

    @Override
    public boolean applicable(BrainContext ctx) {
        return bestFoodSlot(ctx) >= 0;
    }

    @Override
    public double estimateCost(BrainContext ctx) {
        return 0.0; // already carried — the baseline every acquiring method is measured against
    }

    @Override
    public List<Task> decompose(BrainContext ctx) {
        int slot = bestFoodSlot(ctx);
        if (slot < 0) {
            // Unreachable by the Method contract (decompose only follows a true applicable(),
            // same context, same tick); if it ever fires, the executor's call order is broken.
            throw new IllegalStateException("EatFromInventory.decompose with no edible stack — applicable() gates this");
        }
        return List.of(new ConsumeItem(slot));
    }

    @Override
    public String describe() {
        return "eat from inventory";
    }

    /** One tier's running best: strictly-better wins, so slot-order iteration keeps the lowest index on full ties. */
    private record Candidate(int slot, int waste, int nutrition) {
        boolean beats(Candidate incumbent) {
            if (incumbent == null) return true;
            if (waste != incumbent.waste) return waste < incumbent.waste;
            return nutrition > incumbent.nutrition;
        }
    }

    /**
     * The slot policy (see class doc): the best tier-1 slot, else (only when STARVING) the best
     * tier-2 slot; {@code -1} when the policy refuses, which includes {@code missing == 0}.
     */
    private static int bestFoodSlot(BrainContext ctx) {
        Needs needs = ctx.percepts().needs();
        int missing = Needs.MAX_FOOD - needs.foodLevel();
        if (missing == 0) {
            return -1;
        }
        FoodLookup foods = ctx.percepts().foods();
        Candidate ready = null; // tier 1: plain ready food
        Candidate resort = null; // tier 2: raw-but-cookable or canAlwaysEat treats
        for (Inventory.Entry entry : ctx.percepts().inventory().occupied()) {
            Optional<FoodValue> value = foods.of(entry.stack());
            if (value.isEmpty()) {
                continue;
            }
            FoodValue food = value.get();
            Candidate candidate =
                    new Candidate(entry.slot(), Math.max(0, food.nutrition() - missing), food.nutrition());
            boolean lastResort = food.canAlwaysEat() || foods.cookedForm(entry.stack()).isPresent();
            if (lastResort) {
                resort = candidate.beats(resort) ? candidate : resort;
            } else {
                ready = candidate.beats(ready) ? candidate : ready;
            }
        }
        if (ready != null) {
            return ready.slot();
        }
        if (resort != null && needs.band() == Needs.Band.STARVING) {
            return resort.slot();
        }
        return -1;
    }
}
