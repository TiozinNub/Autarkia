package dev.luizloyola.autarkia.core.board;

import dev.luizloyola.anima.core.brain.BrainContext;
import dev.luizloyola.anima.core.brain.sense.Pos;
import dev.luizloyola.anima.core.brain.task.BreakBlock;
import dev.luizloyola.anima.core.brain.task.CompoundTask;
import dev.luizloyola.anima.core.brain.task.EnsureTable;
import dev.luizloyola.anima.core.brain.task.GoTo;
import dev.luizloyola.anima.core.brain.task.Method;
import dev.luizloyola.anima.core.brain.task.PutAwaySurplus;
import dev.luizloyola.anima.core.brain.task.TakeItems;
import dev.luizloyola.anima.core.brain.task.Task;
import dev.luizloyola.anima.core.inv.Inventory;
import dev.luizloyola.anima.core.inv.ItemSpec;
import java.util.ArrayList;
import java.util.List;

/**
 * One trip at one block of a {@link Deconstruct}: a container not known to be empty gives up a
 * pack's worth, carried home; an empty one, or anything else, is broken. The project
 * offers the block again until it is gone, so a full chest is a few trips.
 */
public final class DeconstructErrand implements CompoundTask {

    private final Pos at;
    private final String item;
    private final boolean container;
    private final List<Method> methods = List.of(new Way());

    public DeconstructErrand(Pos at, String item, boolean container) {
        this.at = at;
        this.item = item;
        this.container = container;
    }

    public Pos at() {
        return at;
    }

    public String item() {
        return item;
    }

    public boolean container() {
        return container;
    }

    @Override
    public List<Method> methods() {
        return methods;
    }

    @Override
    public String describe() {
        return "take down " + item + " at (" + at.x() + ", " + at.y() + ", " + at.z() + ")";
    }

    /** Whether the last look inside found nothing; never looked is not empty. */
    private boolean knownEmpty(BrainContext ctx) {
        return ctx.knowledge().insideOf(at).map(seen -> seen.stacks().isEmpty()).orElse(false);
    }

    private final class Way implements Method {
        @Override
        public boolean applicable(BrainContext ctx) {
            return true;
        }

        @Override
        public double estimateCost(BrainContext ctx) {
            return 0.0;
        }

        @Override
        public List<Task> decompose(BrainContext ctx) {
            List<Task> steps = new ArrayList<>();
            Pos stand = EnsureTable.WalkToKnown.standableBeside(at, ctx);
            Pos feet = ctx.percepts().position();
            if (!(feet.x() == stand.x() && feet.y() == stand.y() && feet.z() == stand.z())) {
                steps.add(new GoTo(stand.x(), stand.y(), stand.z()));
            }
            if (container && !knownEmpty(ctx)) {
                // Looking: finding it empty is an answer, and the next trip breaks it.
                steps.add(new TakeItems(at, ItemSpec.ANYTHING, Inventory.ARMOR_START * 64, true));
                steps.add(new PutAwaySurplus(0));
            } else {
                // What it drops is gleaned like any work's drops: no pick-up of its own.
                steps.add(new BreakBlock(at.x(), at.y(), at.z()));
            }
            return steps;
        }

        @Override
        public String describe() {
            return container ? "empty it, then take it down" : "break it";
        }
    }
}
