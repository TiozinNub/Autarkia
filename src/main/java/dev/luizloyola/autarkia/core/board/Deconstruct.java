package dev.luizloyola.autarkia.core.board;

import dev.luizloyola.anima.core.agent.AgentId;
import dev.luizloyola.anima.core.brain.BrainContext;
import dev.luizloyola.anima.core.brain.board.WorkItem;
import dev.luizloyola.anima.core.brain.history.Deed;
import dev.luizloyola.anima.core.brain.sense.Pos;
import dev.luizloyola.anima.core.brain.task.Task;
import dev.luizloyola.anima.core.log.Category;
import dev.luizloyola.anima.core.store.Store;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Take blocks down: a container emptied into the party's stores first, then each block broken, its
 * drop gleaned as any work's is. One item per block, so several members can work at once. Posted when a building
 * replaces the starter base (docs/superpowers/specs/2026-10-01-house-site-design.md, *After the
 * choice*, 5); nothing about it is a house's, so anything the party should take down can use it.
 *
 * <p>Whoever posts it drops the party's claim on each block first, or the emptying would put the
 * goods straight back.
 */
public final class Deconstruct implements PartyProject {

    /** Trips a block may cost before it is given up: a chest the stores cannot take the rest of. */
    static final int MAX_TRIPS = 12;

    /**
     * @param item      what the block drops, picked up once it is broken
     * @param container whether it holds items to move out first
     */
    public record Target(Pos at, String item, boolean container) {
        public Target {
            Objects.requireNonNull(at, "at");
            Objects.requireNonNull(item, "item");
        }
    }

    private final List<Target> targets;
    private final String why;
    private final double priority;
    /** Per target: the trips spent on it. */
    private final Map<Pos, Integer> trips = new LinkedHashMap<>();
    private final Map<Pos, Boolean> gone = new LinkedHashMap<>();
    private final Map<Pos, TargetItem> items = new LinkedHashMap<>();
    private List<WorkItem> offer = List.of();

    public Deconstruct(List<Target> targets, String why, double priority) {
        this.targets = List.copyOf(targets);
        this.why = Objects.requireNonNull(why, "why");
        this.priority = priority;
        for (Target target : this.targets) {
            items.put(target.at(), new TargetItem(target));
        }
        refresh();
    }

    public List<Target> targets() {
        return targets;
    }

    /** The blocks taken down, and those given up. */
    public List<Pos> settled() {
        return List.copyOf(gone.keySet());
    }

    private boolean settled(Target target) {
        return gone.containsKey(target.at());
    }

    private void refresh() {
        List<WorkItem> open = new ArrayList<>();
        for (Target target : targets) {
            if (!settled(target)) {
                open.add(items.get(target.at()));
            }
        }
        offer = List.copyOf(open);
    }

    @Override
    public double priority() {
        return priority;
    }

    @Override
    public List<WorkItem> open() {
        return offer;
    }

    @Override
    public boolean finished() {
        return gone.size() == targets.size();
    }

    @Override
    public void tick(long now) {
    }

    /** A trip came back: the block is read off the world, and a gone one is settled. */
    @Override
    public void completed(WorkItem item, BrainContext ctx) {
        report(item, ctx, true);
    }

    @Override
    public void failed(WorkItem item, BrainContext ctx) {
        report(item, ctx, false);
    }

    @Override
    public void failed(WorkItem item, AgentId who, BrainContext ctx) {
        report(item, ctx, false);
    }

    private void report(WorkItem item, BrainContext ctx, boolean ok) {
        if (!(item instanceof TargetItem mine) || settled(mine.target)) {
            return;
        }
        Target target = mine.target;
        Pos at = target.at();
        String standing = ctx.percepts().blocks().idAt(at.x(), at.y(), at.z());
        if (!target.item().equals(standing)) {
            gone.put(at, true);
            ctx.journal().record(Category.PROJECT, describe(), "took down " + target.item() + " at " + where(at));
        } else {
            int spent = trips.merge(at, 1, Integer::sum);
            if (spent >= MAX_TRIPS) {
                gone.put(at, false);
                ctx.journal().record(Category.PROJECT, describe(), "gave up on " + target.item() + " at "
                        + where(at) + " after " + spent + " trips" + (ok ? "" : " — the last one failed"));
            }
        }
        refresh();
        if (finished()) {
            ctx.journal().record(Category.PROJECT, describe(), "done");
        }
    }

    @Override
    public Optional<WorkKey> keyOf(WorkItem item) {
        return item instanceof TargetItem mine && items.get(mine.target.at()) == mine && !settled(mine.target)
                ? Optional.of(new WorkKey.AtPlace(WorkKey.DECONSTRUCT, mine.target.at()))
                : Optional.empty();
    }

    @Override
    public Optional<WorkItem> itemFor(WorkKey key) {
        if (key instanceof WorkKey.AtPlace place && WorkKey.DECONSTRUCT.equals(place.flavour())) {
            TargetItem item = items.get(place.at());
            if (item != null && !settled(item.target)) {
                return Optional.of(item);
            }
        }
        return Optional.empty();
    }

    @Override
    public String describe() {
        return "take down " + targets.size() + (targets.size() == 1 ? " block" : " blocks") + " — " + why
                + " (" + gone.size() + "/" + targets.size() + ")";
    }

    private static String where(Pos at) {
        return "(" + at.x() + ", " + at.y() + ", " + at.z() + ")";
    }

    private final class TargetItem implements WorkItem {
        private final Target target;

        private TargetItem(Target target) {
            this.target = target;
        }

        @Override
        public double priority() {
            return priority;
        }

        @Override
        public double estimatedCost(BrainContext ctx) {
            double distance = Store.distance(target.at(), ctx.percepts().position());
            return SetUp.COST_AT_RANGE * Math.min(1.0, distance / SetUp.COST_RANGE);
        }

        @Override
        public Task root() {
            return new DeconstructErrand(target.at(), target.item(), target.container());
        }

        @Override
        public Deed doing() {
            return Deed.of(WorkDoings.DECONSTRUCTING);
        }

        @Override
        public String describe() {
            return "take down " + target.item() + " at " + where(target.at());
        }
    }

    // ── continuity ───────────────────────────────────────────────────────────────────────────

    /** @param gone each settled block, true when taken down and false when given up */
    public record State(List<Target> targets, String why, double priority, Map<Pos, Integer> trips,
                        Map<Pos, Boolean> gone) implements ProjectState {
        @Override
        public String type() {
            return "deconstruct";
        }
    }

    @Override
    public State snapshot() {
        return new State(targets, why, priority, Map.copyOf(trips), Map.copyOf(gone));
    }

    public static Deconstruct restore(State state) {
        Deconstruct project = new Deconstruct(state.targets(), state.why(), state.priority());
        project.trips.putAll(state.trips());
        project.gone.putAll(state.gone());
        project.refresh();
        return project;
    }

    public static final ProjectType TYPE = new ProjectType() {
        @Override
        public String id() {
            return "deconstruct";
        }

        @Override
        public Optional<Deconstruct> restore(ProjectState state, long now) {
            return state instanceof State s ? Optional.of(Deconstruct.restore(s)) : Optional.empty();
        }
    };
}
