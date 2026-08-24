package dev.luizloyola.autarkia.core.board;

import dev.luizloyola.anima.core.brain.knowledge.BlockProbe;
import dev.luizloyola.anima.core.brain.knowledge.Coverage;
import dev.luizloyola.anima.core.brain.knowledge.PoiKind;
import dev.luizloyola.anima.core.brain.knowledge.Region;
import dev.luizloyola.anima.core.brain.sense.Pos;
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
     * <p>{@code known} is corner → covered squares for ground somebody has already been over, on the
     * project's grid. {@code coverage} is where this sweep banks what it covers as it covers it, so
     * a re-grant resumes rather than restarts.
     *
     * <p><b>The surveyor must WALK.</b> A {@code Survey} from a standing spot emits only coarse-grid
     * glimpses (<em>woodland roughly there</em>) while the anchors a ledger needs are grown by the
     * near field alone.
     *
     * <p><b>Contract: this task must not SUCCEED until every cell of {@code slice} is known.</b>
     * {@link ClearArea} takes its success as exactly that claim and banks the whole slice covered,
     * which is what stops the slice ever being offered again. A task that succeeds early — on a
     * timeout, on reaching the far corner, on anything short of full coverage — closes the box with
     * whatever is still standing in the part nobody walked. Write off ground no walk can reach, as
     * {@code SurveyArea} does after its walk tries; never simply stop.
     */
    Task survey(Region slice, java.util.Map<Pos, Integer> known, Coverage coverage);

    /** A task that removes the one thing standing at this anchor. */
    Task clear(Pos anchor);

    /**
     * Anchors inside {@code area} that this box must still remove, and that PERCEPTION WILL NEVER
     * NAME. Empty by default, and asked only at the moment a box would otherwise close.
     *
     * <p><b>Why a project needs its own eyes here.</b> Recognising a thing is deliberately stricter
     * than clearing one. {@code TreeRule} refuses a crownless trunk — "a woodpile, a stump, a growth
     * under a roof" — because that guard is what stops a settler reading somebody's log cabin as
     * forest. Felling in dense woodland then makes exactly that shape: a fused canopy goes with the
     * tree that owned it, and the neighbour it sheltered stops being a tree before anyone reaches
     * it. Nothing remembers the stub, so nothing targets it, and the box closes honestly clear over
     * ground that still holds wood (live, 2026-08-23).
     *
     * <p><b>The bounds are the whole safeguard.</b> An operator drew this box, so what it may take
     * inside it is a different question from what a settler may believe about the world at large.
     * Answering here changes nothing outside it, and {@code TreeRule} stays exactly as strict.
     */
    default java.util.List<Pos> residue(Region area, BlockProbe probe) {
        return java.util.List.of();
    }

    /**
     * What removing one of these calls for — {@link Kit#NONE} by default, right for any clearing
     * that works bare-handed. The kind owns this for the same reason it owns {@link #clear}.
     */
    default Kit kit() {
        return Kit.NONE;
    }
}
