package dev.luizloyola.autarkia.core.board;

import dev.luizloyola.anima.core.agent.AgentId;
import dev.luizloyola.anima.core.brain.BrainContext;
import dev.luizloyola.anima.core.brain.board.WorkItem;
import dev.luizloyola.anima.core.brain.history.Deed;
import dev.luizloyola.anima.core.brain.history.Slot;
import dev.luizloyola.anima.core.brain.sense.Pos;
import dev.luizloyola.anima.core.brain.task.Task;
import dev.luizloyola.anima.core.inv.ItemCall;
import dev.luizloyola.anima.core.inv.ItemSpec;
import dev.luizloyola.anima.core.log.Category;
import dev.luizloyola.anima.core.store.Store;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * Somebody comes back to a furnace whose smelting is due (directions spec, decisions 21–23): take
 * out what it made, refuel what is left, carry the output home. One item, posted when the party's
 * record says the process is due.
 *
 * <p><b>The starter first.</b> Only whoever set it going is offered it, until {@link #GRACE} after
 * it fell due; then any member. <b>Next in line</b>: a member holding a job is never offered
 * another, so this waits for the starter's job to end, and its priority puts it above the party's
 * other work when it does — never above an instinct or a need.
 */
public final class Tend implements PartyProject {

    public static final long GRACE = 600;

    /** Over every Direction's work (0.5 at most), so it is the next job taken. */
    public static final double PRIORITY = 0.55;

    private final Pos at;
    private final String output;
    private final @Nullable ItemSpec fuel;
    /** Whether the output goes home: a party with no HOME leaves it in the furnace's taker's pack. */
    private final boolean home;
    private final AgentId starter;
    private final long dueAt;

    private boolean done;
    private final Map<AgentId, Long> cooldownUntil = new LinkedHashMap<>();
    private long lastTick;
    private final TendItem item = new TendItem();

    public Tend(Pos at, String output, @Nullable ItemSpec fuel, boolean home, AgentId starter,
                long dueAt) {
        this.at = Objects.requireNonNull(at, "at");
        this.output = Objects.requireNonNull(output, "output");
        this.fuel = fuel;
        this.home = home;
        this.starter = Objects.requireNonNull(starter, "starter");
        this.dueAt = dueAt;
        this.lastTick = dueAt;
    }

    public Pos at() {
        return at;
    }

    public AgentId starter() {
        return starter;
    }

    public long dueAt() {
        return dueAt;
    }

    @Override
    public double priority() {
        return PRIORITY;
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
        if (until != null && until > lastTick) {
            return false;
        }
        return asker.equals(starter) || lastTick - dueAt > GRACE;
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

    /** The output is cargo on its way home: the stow machinery leaves it be. */
    @Override
    public List<ItemCall> reserved() {
        return done || !home ? List.of() : List.of(ItemCall.need(spec(), TendErrand.LOAD));
    }

    private ItemSpec spec() {
        return ItemSpec.anyOf(Set.of(output));
    }

    @Override
    public ProjectState snapshot() {
        List<Gather.Cooldown> cooldowns = new ArrayList<>();
        cooldownUntil.forEach((who, until) -> cooldowns.add(new Gather.Cooldown(who, until)));
        return new State(at, output, Optional.ofNullable(fuel).map(ItemSpec::name), home, starter, dueAt,
                done, List.copyOf(cooldowns), lastTick);
    }

    @Override
    public Optional<WorkKey> keyOf(WorkItem offered) {
        return offered == item && !done ? Optional.of(new WorkKey.AtPlace(WorkKey.TEND, at)) : Optional.empty();
    }

    @Override
    public Optional<WorkItem> itemFor(WorkKey key) {
        return !done && key.equals(new WorkKey.AtPlace(WorkKey.TEND, at)) ? Optional.of(item) : Optional.empty();
    }

    @Override
    public String describe() {
        return "come back to the furnace at (" + at.x() + ", " + at.y() + ", " + at.z() + ") for " + output;
    }

    private final class TendItem implements WorkItem {
        @Override
        public double priority() {
            return PRIORITY;
        }

        @Override
        public double estimatedCost(BrainContext ctx) {
            double distance = Store.distance(at, ctx.percepts().position());
            return SetUp.COST_AT_RANGE * Math.min(1.0, distance / SetUp.COST_RANGE);
        }

        @Override
        public Task root() {
            return new TendErrand(at, spec(), fuel, home);
        }

        @Override
        public Deed doing() {
            return Deed.of(WorkDoings.TENDING, Slot.item(output));
        }

        @Override
        public String describe() {
            return Tend.this.describe();
        }
    }

    // ── continuity ───────────────────────────────────────────────────────────────────────────

    public record State(Pos at, String output, Optional<String> fuel, boolean home, AgentId starter,
                        long dueAt, boolean done, List<Gather.Cooldown> cooldowns, long lastTick)
            implements ProjectState {
        @Override
        public String type() {
            return "tend";
        }
    }

    public static Optional<Tend> restore(State state, long now) {
        Tend project = new Tend(state.at(), state.output(), state.fuel().flatMap(ItemSpec::byName).orElse(null),
                state.home(),
                state.starter(), state.dueAt());
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
            return "tend";
        }

        @Override
        public Optional<Tend> restore(ProjectState state, long now) {
            return state instanceof State s ? Tend.restore(s, now) : Optional.empty();
        }
    };
}
