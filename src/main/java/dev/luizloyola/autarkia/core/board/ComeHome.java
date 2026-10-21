package dev.luizloyola.autarkia.core.board;

import dev.luizloyola.anima.core.agent.AgentId;
import dev.luizloyola.anima.core.brain.BrainContext;
import dev.luizloyola.anima.core.brain.board.WorkItem;
import dev.luizloyola.anima.core.brain.history.Deed;
import dev.luizloyola.anima.core.brain.sense.Pos;
import dev.luizloyola.anima.core.brain.task.GoTo;
import dev.luizloyola.anima.core.brain.task.Task;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Newcomers walk home: whoever just joined a settled party was told where it lives, and going there
 * is the first thing each takes from the new board (group-up spec, decision 20). One item a newcomer,
 * offered to that newcomer alone; a walk that fails is not offered again.
 */
public final class ComeHome implements PartyProject {

    public static final String FLAVOUR = "come_home";

    private final Pos home;
    private final double priority;
    private final Map<AgentId, Walk> walks = new LinkedHashMap<>();

    public ComeHome(Pos home, List<AgentId> newcomers, double priority) {
        this.home = Objects.requireNonNull(home, "home");
        this.priority = priority;
        newcomers.forEach(who -> walks.put(who, new Walk(who)));
    }

    public Pos home() {
        return home;
    }

    @Override
    public double priority() {
        return priority;
    }

    @Override
    public List<WorkItem> open() {
        return List.copyOf(walks.values());
    }

    @Override
    public boolean offerableTo(WorkItem item, AgentId asker, BrainContext ctx) {
        return item instanceof Walk walk && walk.who.equals(asker);
    }

    @Override
    public boolean finished() {
        return walks.isEmpty();
    }

    @Override
    public void tick(long now) {
    }

    @Override
    public void completed(WorkItem item, BrainContext ctx) {
        walks.values().remove(item);
    }

    @Override
    public void failed(WorkItem item, BrainContext ctx) {
        walks.values().remove(item);
    }

    @Override
    public Optional<WorkKey> keyOf(WorkItem item) {
        return item instanceof Walk walk ? Optional.of(new WorkKey.ForMember(FLAVOUR, walk.who)) : Optional.empty();
    }

    @Override
    public Optional<WorkItem> itemFor(WorkKey key) {
        return key instanceof WorkKey.ForMember member && member.flavour().equals(FLAVOUR)
                ? Optional.ofNullable(walks.get(member.who())) : Optional.empty();
    }

    @Override
    public String describe() {
        return "newcomers walk home — " + walks.size() + " still to come";
    }

    private final class Walk implements WorkItem {
        private final AgentId who;

        private Walk(AgentId who) {
            this.who = who;
        }

        @Override
        public double priority() {
            return priority;
        }

        @Override
        public Task root() {
            return new GoTo(home.x(), home.y(), home.z());
        }

        @Override
        public Deed doing() {
            return Deed.of(WorkDoings.MOVING_IN);
        }

        @Override
        public String describe() {
            return "walk home to the new party";
        }
    }

    public record State(Pos home, List<AgentId> newcomers, double priority) implements ProjectState {
        public State {
            newcomers = List.copyOf(newcomers);
        }

        @Override
        public String type() {
            return "come_home";
        }
    }

    @Override
    public State snapshot() {
        return new State(home, new ArrayList<>(walks.keySet()), priority);
    }

    public static final ProjectType TYPE = new ProjectType() {
        @Override
        public String id() {
            return "come_home";
        }

        @Override
        public Optional<ComeHome> restore(ProjectState state, long now) {
            return state instanceof State s ? Optional.of(new ComeHome(s.home(), s.newcomers(), s.priority()))
                    : Optional.empty();
        }
    };
}
