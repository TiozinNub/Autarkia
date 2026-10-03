package dev.luizloyola.autarkia.core.board;

import dev.luizloyola.anima.core.agent.AgentId;
import dev.luizloyola.anima.core.brain.BrainContext;
import dev.luizloyola.anima.core.brain.board.WorkItem;
import dev.luizloyola.anima.core.brain.history.Deed;
import dev.luizloyola.anima.core.brain.task.Task;
import dev.luizloyola.anima.core.inv.ItemCall;
import dev.luizloyola.anima.core.inv.ItemSpec;
import dev.luizloyola.anima.core.inv.Kit;
import dev.luizloyola.anima.core.log.Category;
import dev.luizloyola.anima.core.social.PartyId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.Set;
import java.util.TreeSet;

/**
 * One far trip for a resource nothing near can give: the party's needs that were priced out of it
 * with their budget at the cap, met together (expedition spec, 2026-10-02).
 *
 * <p><b>The trip is a gather with a longer reach.</b> It walks as far as {@link #REACH} for what the
 * producers already know of, so a source past every budget is mined like one next door. Searching
 * for an unknown source is a later step.
 *
 * <p>One trip at a time, and the expedition is over when one comes home. A need still short after
 * it is priced out again and posts the next.
 */
public final class Expedition implements PartyProject {

    /** Walk-blocks a trip may spend getting there — the HOME search's whole reach *(call)*. */
    public static final double REACH = 512;

    /** The haul is this many times the open needs (Luiz, 2026-10-02: stone, open needs × 2). */
    public static final int HAUL_PER_NEED = 2;

    /**
     * Ticks a need stays without failing again before it leaves: past the longest back-off a
     * priced-out item waits (4,800), so only a need met another way, or withdrawn, ever lapses.
     */
    public static final long NEED_LAPSE = 6000;

    /** A failed trip's wait, doubling for each failure in a row up to 4,800 — SetUp's. */
    public static final int FAIL_COOLDOWN = 600;
    public static final int MOST_DOUBLINGS = 3;

    /**
     * The bid a trip loses to its distance — the most a gather ever costs, since this one goes past
     * every budget *(call)*. Work near HOME at the same priority comes first.
     */
    public static final double COST = Gather.COST_AT_RANGE;

    /**
     * One priced-out item the haul is for.
     *
     * @param what    the item's own description — its name across the restarts it fails through
     * @param pursued what it was crafting, which is what lets the trip seek the resource at all
     * @param seen    the last time it failed priced out
     */
    public record Need(AgentId who, String what, int count, double priority, Set<String> pursued, long seen) {
    }

    /** The trip out, with who took it — written down because nothing re-derives its size. */
    public record Trip(AgentId who, int size) {
    }

    private final ItemSpec resource;
    private final PartyId party;
    private final Split split;
    private final Map<String, Need> needs = new LinkedHashMap<>();

    private TripItem trip;
    /** Whether a hold came back for the trip after a restart — see {@link #holdsRestored}. */
    private boolean tripHeld;
    private int failures;
    private long retryAfter;
    private boolean done;
    private long lastTick;

    private final OfferItem offer = new OfferItem();
    private List<WorkItem> open = List.of();

    public Expedition(ItemSpec resource, PartyId party) {
        this(resource, party, CarrySplit.INSTANCE);
    }

    public Expedition(ItemSpec resource, PartyId party, Split split) {
        this.resource = resource;
        this.party = party;
        this.split = split;
    }

    public ItemSpec resource() {
        return resource;
    }

    // ── the needs ────────────────────────────────────────────────────────────────────────────

    /** One of the party's items failed priced out of this resource with its budget at the cap. */
    public void need(AgentId who, String what, int count, double priority, Set<String> pursued, long now) {
        needs.put(who + "|" + what, new Need(who, what, count, priority, Set.copyOf(pursued), now));
        lastTick = Math.max(lastTick, now);
        rebuildOffer();
    }

    public List<Need> needs() {
        return List.copyOf(needs.values());
    }

    /** {@link #HAUL_PER_NEED} times the open needs. */
    public int haul() {
        int total = 0;
        for (Need need : needs.values()) {
            total += need.count();
        }
        return HAUL_PER_NEED * total;
    }

    private Set<String> pursued() {
        Set<String> all = new TreeSet<>();
        for (Need need : needs.values()) {
            all.addAll(need.pursued());
        }
        return all;
    }

    // ── the beat ─────────────────────────────────────────────────────────────────────────────

    @Override
    public double priority() {
        double best = 0;
        for (Need need : needs.values()) {
            best = Math.max(best, need.priority());
        }
        return best;
    }

    @Override
    public void tick(long now) {
        lastTick = now;
        needs.values().removeIf(need -> need.seen() + NEED_LAPSE < now);
        rebuildOffer();
    }

    @Override
    public boolean finished() {
        return done || (needs.isEmpty() && trip == null);
    }

    @Override
    public List<WorkItem> open() {
        return open;
    }

    private void rebuildOffer() {
        List<WorkItem> items = new ArrayList<>(2);
        if (trip != null) {
            items.add(trip);
        } else if (!needs.isEmpty() && !done) {
            items.add(offer);
        }
        open = List.copyOf(items);
    }

    // ── what one body takes ──────────────────────────────────────────────────────────────────

    @Override
    public boolean owns(WorkItem item) {
        return item == offer || (item instanceof TripItem taken && taken.owner() == this);
    }

    @Override
    public boolean offerableTo(WorkItem item, AgentId asker, BrainContext ctx) {
        if (ctx.depot().isEmpty()) {
            return false;
        }
        if (item == offer) {
            return trip == null && lastTick >= retryAfter && split.tripFor(haul(), resource, ctx) > 0;
        }
        return item == trip && trip.who().equals(asker);
    }

    @Override
    public WorkItem realise(WorkItem offered, AgentId asker, BrainContext ctx) {
        if (offered != offer) {
            return offered;
        }
        int size = split.tripFor(haul(), resource, ctx);
        return size <= 0 ? offered : new TripItem(asker, size, pursued());
    }

    @Override
    public void claimed(WorkItem item, AgentId who) {
        if (item instanceof TripItem taken && owns(taken)) {
            trip = taken;
            tripHeld = true;
            rebuildOffer();
        }
    }

    @Override
    public void completed(WorkItem item, BrainContext ctx) {
        if (item != trip) {
            return;
        }
        trip = null;
        done = true;
        rebuildOffer();
        ctx.journal().record(Category.PROJECT, describe(), "came home");
    }

    @Override
    public void failed(WorkItem item, BrainContext ctx) {
        if (item != trip) {
            return;
        }
        trip = null;
        failures++;
        retryAfter = ctx.percepts().time() + cooldownAfter(failures);
        rebuildOffer();
    }

    static long cooldownAfter(int failures) {
        return (long) FAIL_COOLDOWN << Math.min(MOST_DOUBLINGS, Math.max(0, failures - 1));
    }

    @Override
    public void lapsed(WorkItem item) {
        if (item == trip) {
            trip = null;
            rebuildOffer();
        }
    }

    @Override
    public List<ItemCall> reserved() {
        return trip == null ? List.of() : List.of(ItemCall.need(resource, trip.size()));
    }

    // ── durable names ────────────────────────────────────────────────────────────────────────

    @Override
    public Optional<WorkKey> keyOf(WorkItem item) {
        return item == trip && trip != null
                ? Optional.of(new WorkKey.ForMember(WorkKey.EXPEDITION, trip.who())) : Optional.empty();
    }

    @Override
    public Optional<WorkItem> itemFor(WorkKey key) {
        return keyOf(trip).filter(key::equals).map(found -> trip);
    }

    /** A trip saved without its hold is nobody's: dropped, so the next one can go. */
    @Override
    public void holdsRestored() {
        if (trip != null && !tripHeld) {
            trip = null;
        }
        rebuildOffer();
    }

    // ── the readout ──────────────────────────────────────────────────────────────────────────

    @Override
    public String describe() {
        int count = needs.size();
        return "expedition for " + haul() + " " + resource.name() + " — " + count
                + (count == 1 ? " need" : " needs") + (trip == null ? "" : ", a trip out");
    }

    // ── continuity ───────────────────────────────────────────────────────────────────────────

    public record State(String resource, PartyId party, String split, List<Need> needs, List<Trip> trip,
                        int failures, long retryAfter, long lastTick) implements ProjectState {
        @Override
        public String type() {
            return "expedition";
        }
    }

    @Override
    public State snapshot() {
        List<Trip> out = trip == null ? List.of() : List.of(new Trip(trip.who(), trip.size()));
        return new State(resource.name(), party, split.id(), List.copyOf(needs.values()), out,
                failures, retryAfter, lastTick);
    }

    public static Optional<Expedition> restore(State state, long now) {
        return ItemSpec.byName(state.resource()).map(resource -> {
            Expedition project = new Expedition(resource, state.party(),
                    Splits.byId(state.split()).orElse(CarrySplit.INSTANCE));
            for (Need need : state.needs()) {
                project.needs.put(need.who() + "|" + need.what(), need);
            }
            for (Trip saved : state.trip()) {
                project.trip = project.new TripItem(saved.who(), saved.size(), project.pursued());
            }
            project.failures = state.failures();
            project.retryAfter = state.retryAfter();
            project.lastTick = state.lastTick() >= 0 ? state.lastTick() : now;
            project.rebuildOffer();
            return project;
        });
    }

    public static final ProjectType TYPE = new ProjectType() {
        @Override
        public String id() {
            return "expedition";
        }

        @Override
        public Optional<Expedition> restore(ProjectState state, long now) {
            return state instanceof State s ? Expedition.restore(s, now) : Optional.empty();
        }
    };

    // ── the items ────────────────────────────────────────────────────────────────────────────

    private abstract class Item implements WorkItem {
        @Override
        public double priority() {
            return Expedition.this.priority();
        }

        @Override
        public double estimatedCost(BrainContext ctx) {
            return COST;
        }

        @Override
        public OptionalDouble tolerance() {
            return OptionalDouble.of(REACH);
        }

        @Override
        public boolean buildsOnTheWay() {
            return true;
        }

        @Override
        public Kit kit() {
            return Stock.gatheringKit(resource);
        }

        @Override
        public Deed doing() {
            return Deed.of(WorkDoings.GATHERING, WorkDoings.goods(resource), WorkDoings.FOR_THE_STORES);
        }
    }

    /** What is on offer before anybody takes it — sized by {@link #realise} for whoever does. */
    private final class OfferItem extends Item {
        @Override
        public Task root() {
            throw new IllegalStateException("an expedition's offer is replaced by a trip before it is run");
        }

        @Override
        public String describe() {
            return "fetch " + haul() + " " + resource.name() + " from far off";
        }
    }

    private final class TripItem extends Item {
        private final AgentId who;
        private final int size;
        private final Set<String> pursued;

        private TripItem(AgentId who, int size, Set<String> pursued) {
            this.who = who;
            this.size = size;
            this.pursued = Set.copyOf(pursued);
        }

        AgentId who() {
            return who;
        }

        int size() {
            return size;
        }

        Expedition owner() {
            return Expedition.this;
        }

        @Override
        public Task root() {
            return new GatheringErrand(resource, size, pursued);
        }

        @Override
        public String describe() {
            return "fetch " + size + " " + resource.name() + " from far off";
        }
    }
}
