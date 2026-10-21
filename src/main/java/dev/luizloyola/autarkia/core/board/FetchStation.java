package dev.luizloyola.autarkia.core.board;

import dev.luizloyola.anima.core.agent.AgentId;
import dev.luizloyola.anima.core.brain.BrainContext;
import dev.luizloyola.anima.core.brain.board.WorkItem;
import dev.luizloyola.anima.core.brain.history.Deed;
import dev.luizloyola.anima.core.brain.sense.Pos;
import dev.luizloyola.anima.core.brain.task.Task;
import dev.luizloyola.anima.core.store.Store;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * A store a joining party left far from its new home: one of the newcomers fetches what is in it,
 * and then it is nobody's (group-up spec, decision 21). Done or failed, the place is let go.
 */
public final class FetchStation implements PartyProject {

    public static final String FLAVOUR = "fetch_station";

    private final Pos at;
    private final List<AgentId> newcomers;
    private final double priority;
    private final Item item = new Item();
    private boolean done;

    public FetchStation(Pos at, List<AgentId> newcomers, double priority) {
        this.at = Objects.requireNonNull(at, "at");
        this.newcomers = List.copyOf(newcomers);
        this.priority = priority;
    }

    public Pos at() {
        return at;
    }

    @Override
    public double priority() {
        return priority;
    }

    @Override
    public List<WorkItem> open() {
        return done ? List.of() : List.of(item);
    }

    /** Whoever brought the store with them; the rest of the party never knew it. */
    @Override
    public boolean offerableTo(WorkItem offered, AgentId asker, BrainContext ctx) {
        return newcomers.contains(asker);
    }

    @Override
    public boolean finished() {
        return done;
    }

    @Override
    public void tick(long now) {
    }

    @Override
    public void completed(WorkItem finished, BrainContext ctx) {
        letGo(ctx);
    }

    @Override
    public void failed(WorkItem failed, BrainContext ctx) {
        letGo(ctx);
    }

    private void letGo(BrainContext ctx) {
        ctx.knowledge().places().drop(Store.POI, at);
        done = true;
    }

    @Override
    public Optional<WorkKey> keyOf(WorkItem held) {
        return held == item ? Optional.of(new WorkKey.AtPlace(FLAVOUR, at)) : Optional.empty();
    }

    @Override
    public Optional<WorkItem> itemFor(WorkKey key) {
        return !done && key.equals(new WorkKey.AtPlace(FLAVOUR, at)) ? Optional.of(item) : Optional.empty();
    }

    @Override
    public String describe() {
        return "fetch what was left at (" + at.x() + ", " + at.y() + ", " + at.z() + ")" + (done ? " — done" : "");
    }

    private final class Item implements WorkItem {
        @Override
        public double priority() {
            return priority;
        }

        @Override
        public Task root() {
            return new FetchErrand(at);
        }

        @Override
        public Deed doing() {
            return Deed.of(WorkDoings.MOVING_IN);
        }

        @Override
        public String describe() {
            return "fetch what was left behind";
        }
    }

    public record State(Pos at, List<AgentId> newcomers, double priority, boolean done) implements ProjectState {
        public State {
            newcomers = List.copyOf(newcomers);
        }

        @Override
        public String type() {
            return "fetch_station";
        }
    }

    @Override
    public State snapshot() {
        return new State(at, newcomers, priority, done);
    }

    public static final ProjectType TYPE = new ProjectType() {
        @Override
        public String id() {
            return "fetch_station";
        }

        @Override
        public Optional<FetchStation> restore(ProjectState state, long now) {
            if (!(state instanceof State s)) {
                return Optional.empty();
            }
            FetchStation project = new FetchStation(s.at(), s.newcomers(), s.priority());
            project.done = s.done();
            return Optional.of(project);
        }
    };
}
