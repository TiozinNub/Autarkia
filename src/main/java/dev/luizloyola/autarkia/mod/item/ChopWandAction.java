package dev.luizloyola.autarkia.mod.item;

import dev.luizloyola.anima.compat.sense.LevelProbe;
import dev.luizloyola.anima.core.brain.knowledge.BlockKind;
import dev.luizloyola.anima.core.brain.knowledge.Anchors;
import dev.luizloyola.anima.core.brain.knowledge.GrownRegion;
import dev.luizloyola.anima.core.brain.knowledge.RegionGrowth;
import dev.luizloyola.anima.core.brain.sense.Pos;
import dev.luizloyola.anima.mod.item.WandAction;
import dev.luizloyola.autarkia.core.tree.ChopPlannedTree;
import dev.luizloyola.autarkia.core.tree.TreeRule;
import dev.luizloyola.autarkia.mod.entity.Person;
import java.util.List;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;

/**
 * The wand's first verb: <b>point at a tree, and the selected body fells that tree</b>.
 *
 * <p>Anima's wand knows a coordinate and nothing else; which tree a log belongs to is Autarkia's
 * question (the one {@code /autarkia tree view} paints), so this action answers it and hands the
 * resolved trunk to {@link ChopPlannedTree}.
 *
 * <p><b>Click any cell of it.</b> Log or leaf, stump or top branch: {@link TreeRule} splits the
 * mass the flood reaches the way perception would, and the tree OWNING the clicked cell falls,
 * from its own base. In a fused canopy, which crown you get is which leaf you picked.
 *
 * <p>It does not consult their memory, exactly as {@code /autarkia brain chop <pos>} shortcuts
 * perception — but it does not shortcut the claim: {@code ChopPlannedTree} still refuses a tree
 * somebody else is working. Anything that is not a tree, or is in another dimension, is not
 * claimed at all, and the click falls through to "walk there".
 */
public final class ChopWandAction implements WandAction {

    /**
     * Probe reads per growth step. The wand runs the whole flood in one click rather than budgeting
     * it across ticks like the sensor — {@link RegionGrowth#maxBlocks()} bounds a tree either way.
     */
    private static final int READS_PER_STEP = 4096;

    /** Steps before giving up — a backstop against a growth that never reports done. */
    private static final int MAX_STEPS = 64;

    @Override
    public Optional<Component> useOn(Click click) {
        // Ours only, and this is the species check: asked of what the body is, since the species
        // KEY is a config path Anima never branches on.
        if (!(click.agent() instanceof Person person)) {
            return Optional.empty();
        }
        // The wand points across dimensions happily; a tree in another world is not theirs to fell.
        if (person.level() != click.level()) {
            return Optional.empty();
        }
        BlockPos clicked = click.block();
        Pos seed = new Pos(clicked.getX(), clicked.getY(), clicked.getZ());
        // Their probe, not the operator's: the tree as THEY would read it, placed leaves dismissed
        // and all — so the wand can never order a chop of something they could not have recognized.
        LevelProbe probe = new LevelProbe(person);
        BlockKind kind = probe.at(seed.x(), seed.y(), seed.z());
        if (kind != BlockKind.LOG && kind != BlockKind.LEAVES) {
            return Optional.empty();
        }
        for (GrownRegion.Part tree : treesAround(probe, seed, kind, person)) {
            if (!tree.blocks().containsKey(seed)) {
                continue; // a neighbour sharing the canopy — not the one under the cursor
            }
            // Anchored from the clicked cell: an anchor means the side you came at the thing from.
            boolean autoDisabled = person.brain().run(
                    new ChopPlannedTree(Anchors.choose(tree.approach(), seed)));
            return Optional.of(Component.translatable(autoDisabled
                            ? "item.autarkia.debug_wand.chopping_auto_off"
                            : "item.autarkia.debug_wand.chopping",
                    person.getName(), person.brain().describe()));
        }
        // Wood, but no tree owns it: a woodpile, a crownless stump, a floating remnant, a hedge.
        return Optional.empty();
    }

    /**
     * Floods the clicked cell into its connected mass and splits that mass into individual trees,
     * running to completion in one call. Empty when the backstop tripped — treated as "nothing
     * recognized here" rather than asking for a result the growth would refuse to hand over.
     */
    private static List<GrownRegion.Part> treesAround(
            LevelProbe probe, Pos seed, BlockKind kind, Person person) {
        RegionGrowth growth = new RegionGrowth(TreeRule.INSTANCE, seed, kind, person.profile());
        for (int step = 0; step < MAX_STEPS && !growth.isDone(); step++) {
            growth.step(probe, READS_PER_STEP);
        }
        return growth.isDone() ? growth.result().parts() : List.of();
    }
}
