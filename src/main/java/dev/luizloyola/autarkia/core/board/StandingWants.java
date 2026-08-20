package dev.luizloyola.autarkia.core.board;

import dev.luizloyola.anima.core.brain.BrainContext;
import dev.luizloyola.anima.core.brain.board.WorkItem;
import dev.luizloyola.anima.core.inv.ItemCall;
import dev.luizloyola.anima.core.inv.ItemSpec;
import java.util.List;
import java.util.Set;

/**
 * What this body keeps — a project that posts nothing and only answers {@link #reserved()}.
 *
 * <p><b>It never mints a {@link WorkItem}</b>, and that is deliberate. The personal board has been
 * empty since 2026-08-15 because work should come from real projects, and a settler who went
 * shopping for a tool nobody asked for would quietly reverse that. This says only "if I have one,
 * it is mine", which is what stops the stow machinery putting a settler's own axe in a chest.
 *
 * <p><b>Order is the tiering</b> (decision: Luiz, 2026-08-20): the list is read top-down and the
 * first call that can claim a stack does, so what sits higher survives a pack that has to give
 * something up. Anima never learns what a settler values — it asks what is spoken for and gets an
 * ordered answer.
 */
public final class StandingWants implements PersonalProject {

    private final List<ItemCall> calls;

    public StandingWants(List<ItemCall> calls) {
        this.calls = List.copyOf(calls);
    }

    /**
     * A fresh settler's kit, in tier order: the tools a day's work is made of, then the ones only
     * a particular job wants.
     *
     * <p>Each call names a whole family, so upgrading a wooden axe to iron does not orphan the
     * want. Torches are counted by the stack because they are spent rather than carried — the same
     * distinction {@link ItemCall} draws between a tool and a material.
     */
    public static StandingWants settlerDefaults() {
        return new StandingWants(List.of(
                ItemCall.need(family("axe"), 1),
                ItemCall.need(family("pickaxe"), 1),
                ItemCall.need(family("shovel"), 1),
                ItemCall.need(ItemSpec.anyOf(Set.of("minecraft:torch")), 64),
                ItemCall.want(ItemSpec.anyOf(Set.of("minecraft:shears")), 1),
                ItemCall.want(ItemSpec.anyOf(Set.of("minecraft:flint_and_steel")), 1)));
    }

    /** Every material a vanilla tool of this kind comes in — netherite included, cheaply. */
    private static ItemSpec family(String tool) {
        return ItemSpec.anyOf(Set.of(
                "minecraft:wooden_" + tool, "minecraft:stone_" + tool, "minecraft:iron_" + tool,
                "minecraft:golden_" + tool, "minecraft:diamond_" + tool,
                "minecraft:netherite_" + tool));
    }

    @Override
    public List<ItemCall> reserved() {
        return calls;
    }

    /**
     * Zero, and never read: {@link #open()} mints nothing for a priority to be attached to. It is
     * here because {@link Project} asks every project to name one, not because this one bids.
     */
    @Override
    public double priority() {
        return 0.0;
    }

    @Override
    public List<WorkItem> open() {
        return List.of();
    }

    @Override
    public boolean finished() {
        return false;
    }

    @Override
    public void completed(WorkItem item, BrainContext ctx) {
    }

    @Override
    public void failed(WorkItem item, BrainContext ctx) {
    }

    @Override
    public void tick(BrainContext ctx) {
    }

    @Override
    public String describe() {
        return "keeps " + calls.size() + " kinds of thing";
    }
}
