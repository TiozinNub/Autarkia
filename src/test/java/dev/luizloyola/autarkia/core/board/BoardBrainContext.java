package dev.luizloyola.autarkia.core.board;

import dev.luizloyola.anima.core.agent.AgentId;
import dev.luizloyola.anima.core.agent.AgentProfile;
import dev.luizloyola.anima.core.agent.Needs;
import dev.luizloyola.anima.core.agent.Pronouns;
import dev.luizloyola.anima.core.agent.TestSpecies;
import dev.luizloyola.anima.core.brain.BrainContext;
import dev.luizloyola.anima.core.brain.act.ActuatorAccess;
import dev.luizloyola.anima.core.brain.knowledge.AgentKnowledge;
import dev.luizloyola.anima.core.brain.knowledge.BlockProbe;
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
 * A minimal context for board and project tests: a real pack and a real journal, and a loud
 * refusal for everything else. Layer 3 reads what an agent HOLDS and writes what it decided —
 * anything it reaches for beyond that is a bug the exceptions here will name.
 */
final class BoardBrainContext implements BrainContext {

    private final Inventory inventory = new Inventory();
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
        throw new UnsupportedOperationException("a board never reads memories");
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
