package dev.luizloyola.autarkia.core.brain.task;

import dev.luizloyola.autarkia.core.brain.BrainContext;
import dev.luizloyola.autarkia.core.inv.Inventory;
import java.util.List;

/**
 * Tier 1 of {@link SatisfyHunger}: eat plain food already carried. Applicable exactly when
 * {@link EatSelection#bestReady} finds a ready-tier stack (no strictly-better cooked form, not a
 * {@code canAlwaysEat} treat) and {@code missing > 0}; decomposes to one {@link ConsumeItem} on that
 * slot, equipment slots included — offhand food is how players carry a snack.
 *
 * <p>Cost {@code 0.0}, the free baseline every acquiring method prices against (containers cost path
 * distance, hunting distance-plus-danger), so cheapest-wins prefers ready food whenever any is in
 * hand.
 */
public final class EatReadyFood implements Method {

    @Override
    public boolean applicable(BrainContext ctx) {
        return EatSelection.bestReady(ctx).isPresent();
    }

    @Override
    public double estimateCost(BrainContext ctx) {
        return 0.0; // already carried — the baseline every acquiring method is measured against
    }

    @Override
    public List<Task> decompose(BrainContext ctx) {
        Inventory.Entry entry = EatSelection.bestReady(ctx).orElseThrow(() ->
                // Unreachable by the Method contract (decompose only follows a true applicable(),
                // same context, same tick); if it ever fires, the executor's call order is broken.
                new IllegalStateException(
                        "EatReadyFood.decompose with no ready stack — applicable() gates this"));
        return List.of(new ConsumeItem(entry.slot()));
    }

    @Override
    public String describe() {
        return "eat ready food";
    }
}
