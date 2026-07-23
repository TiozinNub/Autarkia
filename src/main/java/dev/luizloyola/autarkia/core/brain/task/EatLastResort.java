package dev.luizloyola.autarkia.core.brain.task;

import dev.luizloyola.autarkia.core.brain.BrainContext;
import dev.luizloyola.autarkia.core.brain.sense.FoodLookup;
import dev.luizloyola.autarkia.core.inv.Inventory;
import dev.luizloyola.autarkia.core.person.FoodValue;
import java.util.List;

/**
 * Tier 2 of {@link SatisfyHunger}: eat food she would rather not — raw-but-cookable stock, or a
 * {@code canAlwaysEat} treat kept for emergencies. Applicable whenever such a stack is in hand
 * and she has room ({@code missing > 0}); decomposes to a single {@link ConsumeItem} on the best
 * such slot, by the same ordering as {@link EatReadyFood} (see {@link EatSelection}).
 *
 * <p><b>The desperation price.</b> {@link #estimateCost} prices what eating the stack throws
 * away, in the walk-block currency, so the arbiter's cost tolerance decides whether the current
 * hunger can afford it: a raw-but-cookable stack costs
 * {@link #OPPORTUNITY_PER_FORGONE_POINT} × (cooked − raw) nutrition, a {@code canAlwaysEat}
 * treat a flat {@link #TREAT_COST}. A raw potato (1, bakes to 5) forgoes 4 points → 80: hungry
 * ({@code ToleranceCurve} tolerance 60) prices it out, so the root FAILS with the consumer never
 * touched; starving (tolerance ∞) affords it. Manual driving runs at ∞ tolerance too, so an
 * operator-issued eat consumes anything edible.
 *
 * <p><b>Candidate first, price second.</b> The best candidate is picked by the ORDERING and only
 * then priced, so the cost is that of the ordering-best stack, which can differ from the
 * cheapest-to-eat one. The ordering wins, keeping selection deterministic.
 */
public final class EatLastResort implements Method {

    /**
     * Walk-blocks of cost per point of nutrition forgone by eating a raw food instead of cooking
     * it first. Tuned so a raw potato (4 points forgone) prices at {@code 80} — above the HUNGRY
     * tolerance (60), below the STARVING ceiling (∞).
     */
    public static final double OPPORTUNITY_PER_FORGONE_POINT = 20.0;

    /**
     * Flat cost of a {@code canAlwaysEat} treat (golden apple, chorus fruit, ...): the raw-potato
     * level ({@code 80}), so only STARVING affords it.
     */
    public static final double TREAT_COST = 80.0;

    @Override
    public boolean applicable(BrainContext ctx) {
        return EatSelection.bestLastResort(ctx).isPresent();
    }

    @Override
    public double estimateCost(BrainContext ctx) {
        // The chosen candidate's desperation price; +INF stands in for "no candidate" (the executor
        // only calls this after a true applicable(), so that fallback is never the real answer).
        return EatSelection.bestLastResort(ctx).map(entry -> price(ctx, entry))
                .orElse(Double.POSITIVE_INFINITY);
    }

    @Override
    public List<Task> decompose(BrainContext ctx) {
        Inventory.Entry entry = EatSelection.bestLastResort(ctx).orElseThrow(() ->
                new IllegalStateException(
                        "EatLastResort.decompose with no last-resort stack — applicable() gates this"));
        return List.of(new ConsumeItem(entry.slot()));
    }

    @Override
    public String describe() {
        return "eat last resort";
    }

    /**
     * Price the chosen bite: forgone nutrition (cooked − raw) times
     * {@link #OPPORTUNITY_PER_FORGONE_POINT}, else the flat {@link #TREAT_COST}. Cooked-form wins
     * when a stack is both.
     */
    private static double price(BrainContext ctx, Inventory.Entry entry) {
        FoodLookup foods = ctx.percepts().foods();
        var cooked = foods.cookedForm(entry.stack());
        if (cooked.isPresent()) {
            FoodValue raw = foods.of(entry.stack()).orElseThrow(); // last-resort implies edible
            return OPPORTUNITY_PER_FORGONE_POINT * (cooked.get().nutrition() - raw.nutrition());
        }
        return TREAT_COST; // canAlwaysEat treat with no cooked form
    }
}
