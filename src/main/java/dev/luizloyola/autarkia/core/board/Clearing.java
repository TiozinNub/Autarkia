package dev.luizloyola.autarkia.core.board;

import dev.luizloyola.anima.core.brain.knowledge.PoiKind;
import dev.luizloyola.anima.core.brain.knowledge.Region;
import dev.luizloyola.anima.core.brain.sense.Pos;
import dev.luizloyola.anima.core.brain.task.SurveyArea;
import dev.luizloyola.anima.core.brain.task.Task;
import dev.luizloyola.anima.core.inv.Kit;

/**
 * What it means to clear one kind of thing out of a box — the kind, the looking and the removing,
 * the three answers {@link ClearArea} refuses to know for itself.
 *
 * <p>Phases, slices, a ledger and refusals are not about trees. Trees are v1 because the chop is
 * the only root that exists, but Luiz's loop already says "any other trees <em>or blocks</em>", so
 * a clearing that grows to boulders finds its ledger already the right shape.
 */
public interface Clearing {

    /**
     * A stable name for this clearing, written into the party store and resolved through
     * {@link Clearings} on load. Never derived from the class name — a rename would then silently
     * orphan every saved project of that kind.
     */
    String id();

    /** What a target of this clearing is, as the knowledge store knows it. */
    PoiKind kind();

    /** What one of these is called in a readout, plural — {@code "trees"}. */
    String label();

    /**
     * Whether anything can survey for this kind yet. Asked on every host beat, so it must not build
     * anything to answer.
     *
     * <p>{@code false} until ladder step 2 builds the tree surveyor: the project then offers no
     * survey items and says so in its readout.
     */
    boolean surveys();

    /**
     * A task that walks this slice to confidence and comes back knowing what is in it. Called only
     * when {@link #surveys()}.
     *
     * <p>{@code settled} names the min corners of ground a previous pass proved empty and far from
     * anything — skippable, and empty on a first pass.
     *
     * <p><b>The surveyor must WALK.</b> A {@code Survey} from a standing spot emits only coarse-grid
     * glimpses (<em>woodland roughly there</em>) while the anchors a ledger needs are grown by the
     * near field alone.
     */
    Task survey(Region slice, java.util.Set<Pos> settled, SurveyArea.Coverage coverage);

    /** A task that removes the one thing standing at this anchor. */
    Task clear(Pos anchor);

    /**
     * What removing one of these calls for — {@link Kit#NONE} by default, right for any clearing
     * that works bare-handed. The kind owns this for the same reason it owns {@link #clear}.
     */
    default Kit kit() {
        return Kit.NONE;
    }
}
