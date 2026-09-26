package dev.luizloyola.autarkia.core.board;

import dev.luizloyola.anima.core.agent.AgentId;
import dev.luizloyola.anima.core.brain.BrainContext;
import dev.luizloyola.anima.core.brain.board.WorkItem;
import dev.luizloyola.anima.core.brain.history.Deed;
import dev.luizloyola.anima.core.brain.history.Slot;
import dev.luizloyola.anima.core.brain.knowledge.PoiKind;
import dev.luizloyola.anima.core.brain.sense.Pos;
import dev.luizloyola.anima.core.brain.task.PlaceStation;
import dev.luizloyola.anima.core.brain.task.Task;
import dev.luizloyola.anima.core.craft.Workbench;
import dev.luizloyola.anima.core.inv.ItemCall;
import dev.luizloyola.anima.core.inv.ItemSpec;
import dev.luizloyola.anima.core.inv.Kit;
import dev.luizloyola.anima.core.log.Category;
import dev.luizloyola.anima.core.store.Store;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Put stations down near a place, one at a time and in order — a base's workbench, then a chest
 * crafted at it; later one more chest when the base runs short of room.
 *
 * <p><b>One item at a time is the point.</b> A station the party needs is posted once, on its
 * board, so two members can never both decide to build it. When each hauler decided for itself,
 * two crafted a chest for the same yard within a tick of each other (2026-09-24).
 *
 * <p>The station is the item's kit NEED as well as what its root places: the kit-up gets it inside
 * the claim, the board refuses the item to a body that could never have one, and a carried chest is
 * kit rather than cargo, so the stow machinery does not put it away before it is put down.
 */
public final class SetUp implements PartyProject {

    /** One thing a base is built from: what it is remembered as, and the block that places it. */
    public record Station(PoiKind kind, String itemId) {
        public Station {
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(itemId, "itemId");
        }
    }

    public static final Station WORKBENCH = new Station(Workbench.POI, Workbench.ITEM_ID);
    public static final Station STORE = new Station(Store.POI, Store.ITEM_ID);

    /** Ticks one member sits this project out after failing its item — {@code Gather}'s number. */
    public static final int FAIL_COOLDOWN = 600;

    /** The same distance-to-cost mapping every party project bids with. */
    public static final int COST_RANGE = 128;
    public static final double COST_AT_RANGE = 0.25;

    private final List<Station> stations;
    private final Pos near;
    private final double priority;

    /** The station put down next; {@code stations.size()} once they all are. */
    private int next;

    private final Map<AgentId, Long> cooldownUntil = new LinkedHashMap<>();

    /** The one item on offer, rebuilt as {@link #next} moves. The board leases by identity. */
    private StationItem current;

    private long lastTick;

    public SetUp(List<Station> stations, Pos near, double priority) {
        if (stations.isEmpty()) {
            throw new IllegalArgumentException("a set-up with nothing to put down");
        }
        this.stations = List.copyOf(stations);
        this.near = Objects.requireNonNull(near, "near");
        this.priority = priority;
        this.current = new StationItem(this.stations.get(0));
    }

    public List<Station> stations() {
        return stations;
    }

    /** What is still to be put down, in order. */
    public List<Station> remaining() {
        return stations.subList(Math.min(next, stations.size()), stations.size());
    }

    public Pos near() {
        return near;
    }

    @Override
    public double priority() {
        return priority;
    }

    @Override
    public List<WorkItem> open() {
        return finished() ? List.of() : List.of(current);
    }

    @Override
    public boolean finished() {
        return next >= stations.size();
    }

    @Override
    public void tick(long now) {
        lastTick = now;
    }

    @Override
    public boolean offerableTo(WorkItem item, AgentId asker, BrainContext ctx) {
        Long until = cooldownUntil.get(asker);
        return until == null || until <= lastTick;
    }

    /**
     * A station is down and claimed — the root's last step is the claim, so a SUCCESS means the
     * party knows the place. The next station goes on offer, or the project closes with a line in
     * the worker's journal, the one moment a party project holds anybody's context.
     */
    @Override
    public void completed(WorkItem item, BrainContext ctx) {
        if (item != current) {
            return;
        }
        next++;
        if (finished()) {
            ctx.journal().record(Category.PROJECT, describe(), "done");
            return;
        }
        current = new StationItem(stations.get(next));
    }

    @Override
    public void failed(WorkItem item, AgentId who, BrainContext ctx) {
        cooldownUntil.put(who, ctx.percepts().time() + FAIL_COOLDOWN);
    }

    @Override
    public void failed(WorkItem item, BrainContext ctx) {
    }

    @Override
    public ProjectState snapshot() {
        List<Gather.Cooldown> cooldowns = new ArrayList<>();
        cooldownUntil.forEach((who, until) -> cooldowns.add(new Gather.Cooldown(who, until)));
        return new State(stations, near, priority, next, List.copyOf(cooldowns), lastTick);
    }

    @Override
    public Optional<WorkKey> keyOf(WorkItem item) {
        return item == current && !finished()
                ? Optional.of(new WorkKey.AtPlace(WorkKey.SET_UP, near))
                : Optional.empty();
    }

    @Override
    public Optional<WorkItem> itemFor(WorkKey key) {
        return !finished() && key.equals(new WorkKey.AtPlace(WorkKey.SET_UP, near))
                ? Optional.of(current)
                : Optional.empty();
    }

    @Override
    public String describe() {
        List<String> names = new ArrayList<>();
        for (Station station : stations) {
            names.add(station.itemId());
        }
        return "set up " + String.join(", then ", names) + " at " + at(near) + " — "
                + Math.min(next, stations.size()) + "/" + stations.size() + " down";
    }

    private static String at(Pos p) {
        return "(" + p.x() + ", " + p.y() + ", " + p.z() + ")";
    }

    /** Putting one station down near the place. */
    private final class StationItem implements WorkItem {
        private final Station station;

        private StationItem(Station station) {
            this.station = station;
        }

        @Override
        public double priority() {
            return priority;
        }

        @Override
        public double estimatedCost(BrainContext ctx) {
            double distance = Store.distance(near, ctx.percepts().position());
            return COST_AT_RANGE * Math.min(1.0, distance / COST_RANGE);
        }

        @Override
        public Task root() {
            return new PlaceStation(station.kind(), station.itemId(), near);
        }

        @Override
        public Kit kit() {
            return Kit.of(ItemCall.need(ItemSpec.anyOf(Set.of(station.itemId())), 1));
        }

        @Override
        public Deed doing() {
            return Deed.of(WorkDoings.SETTING_UP, Slot.item(station.itemId()));
        }

        @Override
        public String describe() {
            return "put " + station.itemId() + " down at " + at(near);
        }

        @Override
        public String progress(BrainContext ctx) {
            return next + "/" + stations.size() + " down";
        }
    }

    // ── continuity ───────────────────────────────────────────────────────────────────────────

    /** Everything this project is: what to put down, where, how far it got, and who is paced. */
    public record State(List<Station> stations, Pos near, double priority, int next,
                        List<Gather.Cooldown> cooldowns, long lastTick) implements ProjectState {

        /** A state saved before the clock was: the restore's own tick stands in for it. */
        public State(List<Station> stations, Pos near, double priority, int next,
                     List<Gather.Cooldown> cooldowns) {
            this(stations, near, priority, next, cooldowns, -1L);
        }

        @Override
        public String type() {
            return "set_up";
        }
    }

    public static Optional<SetUp> restore(State state, long now) {
        if (state.stations().isEmpty()) {
            return Optional.empty();
        }
        SetUp project = new SetUp(state.stations(), state.near(), state.priority());
        project.next = Math.max(0, state.next());
        if (!project.finished()) {
            project.current = project.new StationItem(project.stations.get(project.next));
        }
        for (Gather.Cooldown cooldown : state.cooldowns()) {
            project.cooldownUntil.put(cooldown.who(), cooldown.retryAfter());
        }
        // The clock it had: it paces who may be offered the station again.
        project.lastTick = state.lastTick() >= 0 ? state.lastTick() : now;
        return Optional.of(project);
    }

    /** What {@link PartyProjects} and the dispatching codec mean by {@code "set_up"}. */
    public static final ProjectType TYPE = new ProjectType() {
        @Override
        public String id() {
            return "set_up";
        }

        @Override
        public Optional<SetUp> restore(ProjectState state, long now) {
            return state instanceof State s ? SetUp.restore(s, now) : Optional.empty();
        }
    };
}
