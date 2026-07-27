package dev.luizloyola.autarkia.core.brain.task;

import dev.luizloyola.autarkia.core.brain.BrainContext;
import dev.luizloyola.autarkia.core.brain.sense.FoodLookup;
import dev.luizloyola.autarkia.core.inv.Inventory;
import dev.luizloyola.autarkia.core.person.FoodValue;
import java.util.List;

/**
 * Tier 2 of {@link SatisfyHunger}: eat food they would rather not — raw-but-cookable stock, or a
 * {@code canAlwaysEat} treat. Applicable when such a stack is in hand and there is room
 * ({@code missing > 0}); decomposes to one {@link ConsumeItem} on the best slot, ordered as in
 * {@link EatReadyFood} (see {@link EatSelection}).
 *
 * <p>Not free: {@link #estimateCost} prices what eating throws away, in the walk-block currency, so
 * the arbiter's cost tolerance decides.
 * <ul>
 *   <li><b>raw-but-cookable</b>: {@link #OPPORTUNITY_PER_FORGONE_POINT} × (cooked − raw). A raw
 *       potato (1, bakes to 5) forgoes 4 points → {@code 80}.</li>
 *   <li><b>{@code canAlwaysEat} treat</b>: a flat {@link #TREAT_COST} ({@code 80}).</li>
 * </ul>
 *
 * <p>A price, not a band-gate: at tolerance 60 the potato is priced out, the compound finds no
 * acceptable way and the root FAILS with the consumer never touched; at ∞ — starving, and manual
 * driving — anything edible is eaten.
 *
 * <p>The ordering-best stack is chosen and only then priced, so the cost can exceed the
 * cheapest-to-eat stack's: pricing gates the chosen bite, it does not select it.
 */
public final class EatLastResort implements Method {

    /**
     * Walk-blocks of cost per point of nutrition forgone by eating raw instead of cooking. Tuned so
     * a raw potato (4 points forgone) prices at {@code 80} — above HUNGRY (60), below STARVING (∞).
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
