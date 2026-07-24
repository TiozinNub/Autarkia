package dev.luizloyola.autarkia.core.brain.board;

import dev.luizloyola.autarkia.core.inv.Inventory;
import dev.luizloyola.autarkia.core.inv.ItemSpec;
import java.util.Locale;
import java.util.Optional;

/**
 * The degenerate personal board: one person carrying one hardcoded standing project,
 * {@code KeepStocked(logs, 16)} (decision: Luiz — every fresh spawn wants a wood stock, and it is
 * easy to see working). Explicitly disposable — real demand is derived, and the wooden-axe goal
 * retires this generator with nothing downstream changing.
 *
 * <p>Every {@link #CHECK_INTERVAL_TICKS} the stock predicate re-reads the pack: the inventory IS
 * the progress record, so completion detection and re-posting need no other state. A failed item
 * cools for {@link #FAIL_COOLDOWN_TICKS} (5× an instinct's — errands retry lazily, reflexes fast).
 * Transient: items regenerate from the predicate, so there is nothing worth persisting yet.
 */
public final class PersonalBoard implements WorkSource {
    /** How often the standing project re-checks its predicate. Tuning knob. */
    public static final int CHECK_INTERVAL_TICKS = 100;
    /** How long a failed item stays off the offer. Tuning knob. */
    public static final int FAIL_COOLDOWN_TICKS = 600;
    /** The placeholder project: keep this many logs in the pack... */
    public static final int STOCK_TARGET = 16;
    /** ...at this priority — beats idling (0.15+stickiness), holds through peckish (0.30),
     *  yields to hungry (0.60). See the work-loop design's behavior table. */
    public static final double STOCK_PRIORITY = 0.35;

    private final ItemSpec spec = ItemSpec.LOGS;

    private WorkItem open;
    private boolean claimed;
    private long nextCheck;
    private long cooldownUntil;
    private long now;
    private int posted;

    /** One board heartbeat — cheap except every {@link #CHECK_INTERVAL_TICKS}th call. */
    public void tick(long now, Inventory inventory) {
        this.now = now;
        if (nextCheck == 0) {
            // Warm-up grace: one full cadence before the first want, so a fresh spawn looks at
            // the world before claiming work in it. Posting on tick one raced the eyes and failed
            // the errand into a pointless cooldown.
            nextCheck = now + CHECK_INTERVAL_TICKS;
            return;
        }
        if (now < nextCheck) {
            return;
        }
        nextCheck = now + CHECK_INTERVAL_TICKS;
        boolean stocked = inventory.count(spec.matcher()) >= STOCK_TARGET;
        if (open == null && !stocked && now >= cooldownUntil) {
            open = new WorkItem("stock-" + (++posted), spec, STOCK_TARGET, STOCK_PRIORITY);
        } else if (open != null && !claimed && stocked) {
            open = null; // the want closed by other means while the item sat unclaimed
        }
    }

    @Override
    public Optional<WorkItem> bestAvailable() {
        return claimed ? Optional.empty() : Optional.ofNullable(open);
    }

    @Override
    public void claim(WorkItem item) {
        claimed = true;
    }

    @Override
    public void complete(WorkItem item) {
        open = null;
        claimed = false;
    }

    @Override
    public void fail(WorkItem item) {
        open = null;
        claimed = false;
        cooldownUntil = now + FAIL_COOLDOWN_TICKS;
    }

    /** The {@code /autarkia board} readout. */
    public String describe() {
        StringBuilder text = new StringBuilder("board: keep ")
                .append(STOCK_TARGET).append(' ').append(spec.name())
                .append(String.format(Locale.ROOT, " (priority %.2f)", STOCK_PRIORITY))
                .append(" — ");
        if (open == null) {
            text.append(now < cooldownUntil
                    ? "cooling down (" + (cooldownUntil - now) + "t left)"
                    : "no open item");
        } else {
            text.append(open.describe()).append(claimed ? " [claimed]" : " [on offer]");
        }
        return text.toString();
    }
}
