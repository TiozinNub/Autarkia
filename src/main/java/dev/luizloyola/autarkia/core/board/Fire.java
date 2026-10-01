package dev.luizloyola.autarkia.core.board;

import dev.luizloyola.anima.core.agent.AgentId;
import dev.luizloyola.anima.core.brain.BrainContext;
import dev.luizloyola.anima.core.brain.board.WorkItem;
import dev.luizloyola.anima.core.brain.history.Deed;
import dev.luizloyola.anima.core.brain.history.Slot;
import dev.luizloyola.anima.core.brain.sense.Pos;
import dev.luizloyola.anima.core.brain.task.LoadFurnace;
import dev.luizloyola.anima.core.brain.task.Task;
import dev.luizloyola.anima.core.inv.ItemSpec;
import dev.luizloyola.anima.core.log.Category;
import dev.luizloyola.anima.core.store.Store;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Load the party's furnace once — {@code count} of {@code input} with fuel for all of it. One item,
 * one taker. Coming back for what it makes is {@link Tend}'s, posted when it falls due; loading
 * sets that going.
 */
public final class Fire implements PartyProject {

    private final Pos at;
    private final ItemSpec input;
    private final int count;
    private final ItemSpec fuel;
    private final double priority;

    private boolean done;
    private final Map<AgentId, Long> cooldownUntil = new LinkedHashMap<>();
    private long lastTick;
    private final FireItem item = new FireItem();

    public Fire(Pos at, ItemSpec input, int count, ItemSpec fuel, double priority) {
        this.at = Objects.requireNonNull(at, "at");
        this.input = Objects.requireNonNull(input, "input");
        this.count = Math.max(1, Math.min(64, count));
        this.fuel = Objects.requireNonNull(fuel, "fuel");
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
        return until == null || until <= lastTick;
    }

    @Override
    public void completed(WorkItem offered, BrainContext ctx) {
        done = true;
        ctx.journal().record(Category.PROJECT, describe(), "done");
    }

    @Override
    public void failed(WorkItem offered, AgentId who, BrainContext ctx) {
        cooldownUntil.put(who, ctx.percepts().time() + SetUp.FAIL_COOLDOWN);
    }

    @Override
    public void failed(WorkItem offered, BrainContext ctx) {
    }

    @Override
    public ProjectState snapshot() {
        List<Gather.Cooldown> cooldowns = new ArrayList<>();
        cooldownUntil.forEach((who, until) -> cooldowns.add(new Gather.Cooldown(who, until)));
        return new State(at, input.name(), count, fuel.name(), priority, done, List.copyOf(cooldowns), lastTick);
    }

    @Override
    public Optional<WorkKey> keyOf(WorkItem offered) {
        return offered == item && !done ? Optional.of(new WorkKey.AtPlace(WorkKey.FIRE, at)) : Optional.empty();
    }

    @Override
    public Optional<WorkItem> itemFor(WorkKey key) {
        return !done && key.equals(new WorkKey.AtPlace(WorkKey.FIRE, at)) ? Optional.of(item) : Optional.empty();
    }

    @Override
    public String describe() {
        return "load the furnace at (" + at.x() + ", " + at.y() + ", " + at.z() + ") with " + count + " "
                + input.name();
    }

    private final class FireItem implements WorkItem {
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
            return new LoadFurnace(at, input, count, fuel);
        }

        @Override
        public Deed doing() {
            return Deed.of(WorkDoings.FIRING, Slot.item(input.name()));
        }

        @Override
        public String describe() {
            return Fire.this.describe();
        }
    }

    // ── continuity ───────────────────────────────────────────────────────────────────────────

    public record State(Pos at, String input, int count, String fuel, double priority, boolean done,
                        List<Gather.Cooldown> cooldowns, long lastTick) implements ProjectState {
        @Override
        public String type() {
            return "fire";
        }
    }

    public static Optional<Fire> restore(State state, long now) {
        Optional<ItemSpec> input = ItemSpec.byName(state.input());
        Optional<ItemSpec> fuel = ItemSpec.byName(state.fuel());
        if (input.isEmpty() || fuel.isEmpty()) {
            return Optional.empty();
        }
        Fire project = new Fire(state.at(), input.get(), state.count(), fuel.get(), state.priority());
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
            return "fire";
        }

        @Override
        public Optional<Fire> restore(ProjectState state, long now) {
            return state instanceof State s ? Fire.restore(s, now) : Optional.empty();
        }
    };
}
