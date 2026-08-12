package dev.luizloyola.autarkia.core.board;

import dev.luizloyola.anima.core.agent.AgentId;
import dev.luizloyola.anima.core.agent.AgentProfile;
import dev.luizloyola.anima.core.agent.Metabolism;
import dev.luizloyola.anima.core.agent.need.Needs;
import dev.luizloyola.anima.core.agent.Pronouns;
import dev.luizloyola.anima.core.agent.TestSpecies;
import dev.luizloyola.anima.core.brain.BrainContext;
import dev.luizloyola.anima.core.brain.act.ActuatorAccess;
import dev.luizloyola.anima.core.brain.knowledge.AgentKnowledge;
import dev.luizloyola.anima.core.brain.knowledge.BlockProbe;
import dev.luizloyola.anima.core.brain.knowledge.PoiKind;
import dev.luizloyola.anima.core.brain.knowledge.PoiMemory;
import dev.luizloyola.anima.core.brain.knowledge.Region;
import dev.luizloyola.anima.core.brain.sense.Being;
import dev.luizloyola.anima.core.brain.sense.Drop;
import dev.luizloyola.anima.core.brain.sense.FoodLookup;
import dev.luizloyola.anima.core.brain.sense.Percepts;
import dev.luizloyola.anima.core.brain.sense.Pos;
import dev.luizloyola.anima.core.inv.Inventory;
import dev.luizloyola.anima.core.log.AgentJournal;
import dev.luizloyola.anima.core.log.JournalService;
import java.util.List;

/**
 * A minimal context for board and project tests: a real pack, a real journal, a real knowledge
 * store, and an exception for everything else. Layer 3 reads what an agent holds and knows when they
 * report and writes what it decided; reaching further is a bug these exceptions name.
 *
 * <p>The knowledge store arrived with {@code ClearArea}: a surveyor's report is their memory of the
 * box at hand-over — one read of a member's mind, not the ambient telepathy the "board is not
 * omniscient" rule forbids.
 */
final class BoardBrainContext implements BrainContext {

    private final Inventory inventory = new Inventory();
    private final AgentKnowledge knowledge = new AgentKnowledge();
    private final JournalService journal = new JournalService(() -> 0L);
    private final AgentJournal view = journal.forPerson(AgentId.random());
    /** What an item is allowed to price itself against — the one map fact a board test needs. */
    private Pos position = new Pos(0, 0, 0);
    /** The clock every hold is measured against; tests move it to make a lease lapse. */
    private long now;

    Inventory inventory() {
        return inventory;
    }

    void standAt(Pos where) {
        this.position = where;
    }

    long now() {
        return now;
    }

    /** Moves the world clock forward — the only way a lease dies. */
    void advance(long ticks) {
        this.now += ticks;
    }

    @Override
    public AgentProfile profile() {
        return TestSpecies.PROFILE;
    }

    @Override
    public ActuatorAccess actuators() {
        throw new UnsupportedOperationException("a board never acts");
    }

    @Override
    public Pronouns pronouns() {
        return Pronouns.THEY; // a board never narrates
    }

    @Override
    public Percepts percepts() {
        return new Percepts() {
            @Override
            public Inventory inventory() {
                return inventory;
            }

            @Override
            public Pos position() {
                return position;
            }

            @Override
            public Metabolism metabolism() {
                throw new UnsupportedOperationException();
            }

            @Override
            public Needs needs() {
                throw new UnsupportedOperationException();
            }

            @Override
            public FoodLookup foods() {
                throw new UnsupportedOperationException();
            }

            @Override
            public List<Being> beings() {
                return List.of();
            }

            @Override
            public BlockProbe blocks() {
                throw new UnsupportedOperationException();
            }

            @Override
            public List<Drop> drops() {
                throw new UnsupportedOperationException();
            }

            @Override
            public long time() {
                return now;
            }
        };
    }

    @Override
    public AgentJournal journal() {
        return view;
    }

    @Override
    public AgentKnowledge knowledge() {
        return knowledge;
    }

    /**
     * Drops a remembered place — what felling one actually does. {@code ChopPlannedTree} forgets
     * the anchor on both the success and the ghost path, so a test that fells without forgetting
     * is modelling a body that cannot learn.
     */
    void forget(PoiKind kind, Pos anchor) {
        knowledge.forget(kind, anchor);
    }

    /** Puts a remembered place of this kind at this anchor — what a surveyor comes back with. */
    void remember(PoiKind kind, Pos anchor) {
        knowledge.note(new PoiMemory(kind, anchor, Region.of(anchor), 1, false, now),
                AgentKnowledge.maxPerKind(profile()));
    }

    @Override
    public double costTolerance() {
        return Double.POSITIVE_INFINITY;
    }

    /** A fixed stream, so a test that draws twice gets the same two numbers every run. */
    private final dev.luizloyola.anima.core.agent.AgentRandom random =
            new dev.luizloyola.anima.core.agent.AgentRandom(20260805L);

    @Override
    public java.util.random.RandomGenerator random() {
        return random;
    }
}
