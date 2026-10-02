package dev.luizloyola.autarkia.core.board;

import dev.luizloyola.anima.core.agent.AgentId;
import dev.luizloyola.anima.core.brain.BrainContext;
import dev.luizloyola.anima.core.brain.board.WorkItem;
import dev.luizloyola.anima.core.brain.history.Deed;
import dev.luizloyola.anima.core.brain.knowledge.Region;
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
import java.util.Set;
import java.util.TreeSet;

/**
 * Pull up the ground's plants over a box — grass, flowers, leaf litter, saplings
 * (docs/superpowers/specs/2026-10-01-home-area-design.md, decision 4). Felling takes the trees;
 * this takes what grows under them, so the ground is ready before anything is built on it.
 *
 * <p>The box is cut into strips {@value #STRIP} blocks deep, one item each, so a crew works a chunk
 * side by side. A strip is done when its worker has pulled up everything it saw there; one that
 * {@value #GIVE_UP} members have failed at is written off, so a plant nobody can reach never keeps
 * the box open, and one a member has failed at {@value #STRIKE} times is never offered to them
 * again: a settler cut off from it by a ravine claimed it 268 times in ten minutes (forest,
 * 2026-10-02).
 */
public final class ClearPlants implements PartyProject {

    static final int STRIP = 4;
    static final int GIVE_UP = 3;
    static final int STRIKE = 3;

    private final Region bounds;
    private final double priority;
    private final List<Region> strips;
    private final Set<Integer> done = new TreeSet<>();
    /** Each strip's failures, by member. */
    private final Map<Integer, Map<AgentId, Integer>> failedBy = new LinkedHashMap<>();
    private final Map<AgentId, Long> cooldownUntil = new LinkedHashMap<>();
    private final List<StripItem> items = new ArrayList<>();
    private long lastTick;

    public ClearPlants(Region bounds, double priority) {
        this.bounds = Objects.requireNonNull(bounds, "bounds");
        this.priority = priority;
        this.strips = strips(bounds);
        for (int i = 0; i < strips.size(); i++) {
            items.add(new StripItem(i));
        }
    }

    private static List<Region> strips(Region bounds) {
        List<Region> strips = new ArrayList<>();
        for (int z = bounds.min().z(); z <= bounds.max().z(); z += STRIP) {
            strips.add(new Region(new Pos(bounds.min().x(), bounds.min().y(), z),
                    new Pos(bounds.max().x(), bounds.max().y(), Math.min(bounds.max().z(), z + STRIP - 1))));
        }
        return strips;
    }

    public Region bounds() {
        return bounds;
    }

    @Override
    public double priority() {
        return priority;
    }

    @Override
    public List<WorkItem> open() {
        List<WorkItem> open = new ArrayList<>();
        for (StripItem item : items) {
            if (!done.contains(item.index)) {
                open.add(item);
            }
        }
        return open;
    }

    @Override
    public boolean finished() {
        return done.size() == strips.size();
    }

    @Override
    public void tick(long now) {
        lastTick = now;
    }

    @Override
    public boolean offerableTo(WorkItem offered, AgentId asker, BrainContext ctx) {
        if (offered instanceof StripItem item && failures(item.index, asker) >= STRIKE) {
            return false;
        }
        Long until = cooldownUntil.get(asker);
        return until == null || until <= lastTick;
    }

    private int failures(int strip, AgentId who) {
        return failedBy.getOrDefault(strip, Map.of()).getOrDefault(who, 0);
    }

    @Override
    public void completed(WorkItem offered, BrainContext ctx) {
        if (offered instanceof StripItem item) {
            done.add(item.index);
            if (finished()) {
                ctx.journal().record(Category.PROJECT, describe(), "done");
            }
        }
    }

    @Override
    public void failed(WorkItem offered, AgentId who, BrainContext ctx) {
        cooldownUntil.put(who, ctx.percepts().time() + SetUp.FAIL_COOLDOWN);
        if (!(offered instanceof StripItem item)) {
            return;
        }
        Map<AgentId, Integer> by = failedBy.computeIfAbsent(item.index, key -> new LinkedHashMap<>());
        int times = by.merge(who, 1, Integer::sum);
        if (times == 1 && by.size() >= GIVE_UP) {
            done.add(item.index);
            ctx.journal().record(Category.PROJECT, describe(), "wrote off " + item.describe() + " after "
                    + GIVE_UP + " of us failed at it");
        } else if (times == STRIKE) {
            ctx.journal().record(Category.PROJECT, describe(), "gave up " + item.describe() + " after "
                    + STRIKE + " tries");
        }
    }

    @Override
    public void failed(WorkItem offered, BrainContext ctx) {
    }

    @Override
    public Optional<WorkKey> keyOf(WorkItem offered) {
        return offered instanceof StripItem item && items.contains(item) && !done.contains(item.index)
                ? Optional.of(key(item.index)) : Optional.empty();
    }

    @Override
    public Optional<WorkItem> itemFor(WorkKey key) {
        for (StripItem item : items) {
            if (!done.contains(item.index) && key(item.index).equals(key)) {
                return Optional.of(item);
            }
        }
        return Optional.empty();
    }

    private WorkKey key(int index) {
        return new WorkKey.AtPlace(WorkKey.CLEAR_PLANTS, strips.get(index).min());
    }

    @Override
    public String describe() {
        return "clear the plants in " + (bounds.max().x() - bounds.min().x() + 1) + "×"
                + (bounds.max().z() - bounds.min().z() + 1) + " at (" + bounds.min().x() + ", "
                + bounds.min().z() + "), " + done.size() + "/" + strips.size() + " strips";
    }

    private final class StripItem implements WorkItem {
        private final int index;

        StripItem(int index) {
            this.index = index;
        }

        @Override
        public double priority() {
            return priority;
        }

        @Override
        public double estimatedCost(BrainContext ctx) {
            Region strip = strips.get(index);
            Pos middle = new Pos((strip.min().x() + strip.max().x()) / 2, ctx.percepts().position().y(),
                    (strip.min().z() + strip.max().z()) / 2);
            return SetUp.COST_AT_RANGE * Math.min(1.0, Store.distance(middle, ctx.percepts().position())
                    / SetUp.COST_RANGE);
        }

        @Override
        public Task root() {
            return new ClearStrip(strips.get(index));
        }

        /** As every other errand's: a strip across a ravine is reached by a deck (forest, 2026-10-02). */
        @Override
        public boolean buildsOnTheWay() {
            return true;
        }

        @Override
        public Deed doing() {
            return Deed.of(WorkDoings.CLEARING_PLANTS);
        }

        @Override
        public String describe() {
            Region strip = strips.get(index);
            return "the strip at (" + strip.min().x() + ", " + strip.min().z() + ")";
        }
    }

    // ── continuity ───────────────────────────────────────────────────────────────────────────

    /** @param failures each strip with the members who failed at it, so a write-off survives a restart */
    public record State(Region bounds, double priority, List<Integer> done, List<Failure> failures,
                        List<Gather.Cooldown> cooldowns, long lastTick) implements ProjectState {
        @Override
        public String type() {
            return "clear_plants";
        }
    }

    /** @param times how often {@code who} failed at the strip, so a member's strike survives too */
    public record Failure(int strip, AgentId who, int times) {
        public Failure(int strip, AgentId who) {
            this(strip, who, 1);
        }
    }

    @Override
    public ProjectState snapshot() {
        List<Failure> failures = new ArrayList<>();
        failedBy.forEach((strip, by) -> by.forEach((member, times) -> failures.add(new Failure(strip, member, times))));
        List<Gather.Cooldown> cooldowns = new ArrayList<>();
        cooldownUntil.forEach((who, until) -> cooldowns.add(new Gather.Cooldown(who, until)));
        return new State(bounds, priority, List.copyOf(done), failures, cooldowns, lastTick);
    }

    public static Optional<ClearPlants> restore(State state, long now) {
        ClearPlants project = new ClearPlants(state.bounds(), state.priority());
        for (int strip : state.done()) {
            if (strip >= 0 && strip < project.strips.size()) {
                project.done.add(strip);
            }
        }
        for (Failure failure : state.failures()) {
            project.failedBy.computeIfAbsent(failure.strip(), key -> new LinkedHashMap<>())
                    .merge(failure.who(), Math.max(1, failure.times()), Integer::sum);
        }
        for (Gather.Cooldown cooldown : state.cooldowns()) {
            project.cooldownUntil.put(cooldown.who(), cooldown.retryAfter());
        }
        project.lastTick = state.lastTick() >= 0 ? state.lastTick() : now;
        return Optional.of(project);
    }

    public static final ProjectType TYPE = new ProjectType() {
        @Override
        public String id() {
            return "clear_plants";
        }

        @Override
        public Optional<ClearPlants> restore(ProjectState state, long now) {
            return state instanceof State s ? ClearPlants.restore(s, now) : Optional.empty();
        }
    };
}
