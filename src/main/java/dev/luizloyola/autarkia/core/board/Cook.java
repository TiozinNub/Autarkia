package dev.luizloyola.autarkia.core.board;

import dev.luizloyola.anima.core.agent.AgentId;
import dev.luizloyola.anima.core.brain.BrainContext;
import dev.luizloyola.anima.core.brain.board.WorkItem;
import dev.luizloyola.anima.core.brain.history.Deed;
import dev.luizloyola.anima.core.brain.history.Slot;
import dev.luizloyola.anima.core.brain.sense.Pos;
import dev.luizloyola.anima.core.brain.gate.Act;
import dev.luizloyola.anima.core.brain.gate.Acts;
import dev.luizloyola.anima.core.brain.task.RawFood;
import dev.luizloyola.anima.core.brain.task.Task;
import dev.luizloyola.anima.core.craft.Campfire;
import dev.luizloyola.anima.core.log.Category;
import dev.luizloyola.anima.core.store.Store;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Cook HOME's raw food at the party's campfire and bring it home cooked (directions spec, decision
 * 25) — the food line's way to raise its points before it sends anybody out for more. One item,
 * one taker, who stays at the fire until it is all done (decision 26).
 */
public final class Cook implements PartyProject {

    /** Cooking for the party, opened by Wood. */
    public static final Act ACT = Acts.register(new Act("autarkia:cook"));

    private final Pos at;
    private final int count;
    private final double priority;

    private boolean done;
    private final Map<AgentId, Long> cooldownUntil = new LinkedHashMap<>();
    private long lastTick;
    private final CookItem item = new CookItem();

    public Cook(Pos at, int count, double priority) {
        this.at = Objects.requireNonNull(at, "at");
        this.count = Math.max(1, Math.min(64, count));
        this.priority = priority;
    }

    public Pos at() {
        return at;
    }

    public int count() {
        return count;
    }

    @Override
    public double priority() {
        return priority;
    }

    @Override
    public List<WorkItem> open() {
        return done ? List.of() : List.of(item);
    }

    @Override
    public boolean finished() {
        return done;
    }

    @Override
    public void tick(long now) {
        lastTick = now;
    }

    @Override
    public boolean offerableTo(WorkItem offered, AgentId asker, BrainContext ctx) {
        Long until = cooldownUntil.get(asker);
        return (until == null || until <= lastTick) && ctx.gate().mayDo(ACT);
    }

    @Override
    public void completed(WorkItem offered, BrainContext ctx) {
        done = true;
        ctx.journal().record(Category.PROJECT, describe(), "done");
    }

    @Override
    public void failed(WorkItem offered, AgentId who, BrainContext ctx) {
        if (campfireGone(ctx, at)) {
            done = true;
            ctx.journal().record(Category.PROJECT, describe(), "withdrawn — no campfire there");
            return;
        }
        cooldownUntil.put(who, ctx.percepts().time() + SetUp.FAIL_COOLDOWN);
    }

    /** Whether the party no longer has a campfire at {@code at}, as {@link Fire#furnaceGone}. */
    static boolean campfireGone(BrainContext ctx, Pos at) {
        return ctx.knowledge().places().all(Campfire.POI).stream().noneMatch(row -> row.at().equals(at));
    }

    @Override
    public void failed(WorkItem offered, BrainContext ctx) {
    }

    @Override
    public ProjectState snapshot() {
        List<Gather.Cooldown> cooldowns = new ArrayList<>();
        cooldownUntil.forEach((who, until) -> cooldowns.add(new Gather.Cooldown(who, until)));
        return new State(at, count, priority, done, List.copyOf(cooldowns), lastTick);
    }

    @Override
    public Optional<WorkKey> keyOf(WorkItem offered) {
        return offered == item && !done ? Optional.of(new WorkKey.AtPlace(WorkKey.COOK, at)) : Optional.empty();
    }

    @Override
    public Optional<WorkItem> itemFor(WorkKey key) {
        return !done && key.equals(new WorkKey.AtPlace(WorkKey.COOK, at)) ? Optional.of(item) : Optional.empty();
    }

    @Override
    public String describe() {
        return "cook " + count + " raw food at the campfire at (" + at.x() + ", " + at.y() + ", " + at.z() + ")";
    }

    private final class CookItem implements WorkItem {
        @Override
        public double priority() {
            return priority;
        }

        @Override
        public double estimatedCost(BrainContext ctx) {
            double distance = Store.distance(at, ctx.percepts().position());
            return SetUp.COST_AT_RANGE * Math.min(1.0, distance / SetUp.COST_RANGE);
        }

        @Override
        public Task root() {
            return new CookErrand(at, count);
        }

        @Override
        public Deed doing() {
            return Deed.of(WorkDoings.COOKING, Slot.item(RawFood.SPEC.name()));
        }

        @Override
        public String describe() {
            return Cook.this.describe();
        }
    }

    // ── continuity ───────────────────────────────────────────────────────────────────────────

    public record State(Pos at, int count, double priority, boolean done, List<Gather.Cooldown> cooldowns,
                        long lastTick) implements ProjectState {
        @Override
        public String type() {
            return "cook";
        }
    }

    public static Optional<Cook> restore(State state, long now) {
        Cook project = new Cook(state.at(), state.count(), state.priority());
        project.done = state.done();
        for (Gather.Cooldown cooldown : state.cooldowns()) {
            project.cooldownUntil.put(cooldown.who(), cooldown.retryAfter());
        }
        project.lastTick = state.lastTick() >= 0 ? state.lastTick() : now;
        return Optional.of(project);
    }

    public static final ProjectType TYPE = new ProjectType() {
        @Override
        public String id() {
            return "cook";
        }

        @Override
        public Optional<Cook> restore(ProjectState state, long now) {
            return state instanceof State s ? Cook.restore(s, now) : Optional.empty();
        }
    };
}
