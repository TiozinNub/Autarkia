package dev.luizloyola.autarkia.core.board;

import dev.luizloyola.anima.core.agent.AgentId;
import dev.luizloyola.anima.core.agent.ProfileAspect;
import dev.luizloyola.anima.core.brain.BrainContext;
import dev.luizloyola.anima.core.brain.board.WorkItem;
import dev.luizloyola.anima.core.brain.knowledge.AgentKnowledge;
import dev.luizloyola.anima.core.brain.knowledge.PoiMemory;
import dev.luizloyola.anima.core.brain.sense.Pos;
import dev.luizloyola.anima.core.brain.task.Task;
import dev.luizloyola.anima.core.inv.ItemCall;
import dev.luizloyola.anima.core.inv.ItemSpec;
import dev.luizloyola.anima.core.log.Category;
import dev.luizloyola.anima.core.social.PartyId;
import dev.luizloyola.anima.core.store.Store;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Get this much of this into that yard — the second kind of party project, and the first whose goal
 * is a QUANTITY rather than a place.
 *
 * <h2>The ledger is the chest as last read</h2>
 *
 * <p>One {@link Reading} per yard chest, {@link #stored()} their sum, and {@link #finished()} is
 * {@code stored >= target} — context-free, reading nothing but its own state, which is what a
 * project ticking in a host with nobody's eyes has to be. The two alternatives were both ruled out
 * on 2026-08-18: a monotonic tally of deliveries never decrements and so closes satisfied over an
 * emptied chest, and a sum over member packs cannot be asked at all without somebody's eyes.
 *
 * <p><b>The reading arrives free.</b> {@code PutItems} writes {@code sawInside} as it deposits, so
 * by the time {@link #completed} fires the depositor already remembers what is in that chest and
 * {@link AgentKnowledge.Seen#count} reads it off. The board never looks at the world itself — the
 * not-omniscient rule holding by construction rather than by discipline.
 *
 * <h2>One trip per member, sized at mint</h2>
 *
 * <p>{@link #tick} mints one item per member with nothing live, sized by the {@link Split}. Fixing
 * the size at mint is what stops four settlers each fetching a full 64 for a gather of 64, and it
 * makes {@link #inFlight()} a plain sum rather than a guess. When the remainder reaches zero the
 * project simply stops minting: a member who asks is offered nothing <em>by this project</em> and
 * the composite hands them whatever scores next. There is no reserved-and-idle state anywhere.
 */
public final class Gather implements PartyProject {

    /**
     * Distance at which a trip costs the most it can, and how much that is — the same mapping and
     * the same numbers {@code ClearArea} prices its errands with, so the two kinds of party work
     * compete on one scale. Without it a gather posted across the world would outbid a clearing
     * underfoot, since the default item cost is zero.
     */
    public static final int COST_RANGE = 128;
    public static final double COST_AT_RANGE = 0.25;

    /**
     * Ticks ONE MEMBER sits out THIS project after a trip of it fails — {@code KeepStocked}'s own
     * number, for the same reason a quantity project needs any pacing at all: without it, a project
     * failing everywhere re-offers the identical doomed trip to the same member every beat, and
     * {@code Board.benchIfFlailing} spends their whole four-strike budget on it alone and stands
     * them down from every OTHER job too. Live, 2026-08-24: a jungle gather with no jungle in reach
     * benched a settler off a perfectly reachable oak job standing 26 blocks away, over and over.
     *
     * <p>Scoped to the MEMBER, not the project: unlike a place-named errand, "no jungle in reach" is
     * a fact about one body's surroundings, and a settler who happens to be standing in a jungle
     * must still be offered the job.
     */
    public static final int FAIL_COOLDOWN = 600;

    /**
     * What one yard chest held the last time anybody looked, and when.
     *
     * @param at the game time of the look — carried so a restart cannot make a stale belief look
     *           fresh, and so a reporter with an older memory cannot overwrite a newer reading
     */
    public record Reading(Pos chest, int count, long at) {
    }

    /**
     * One member waiting out a failed trip — the same pacing {@code ClearArea.SliceCooldown} gives
     * a slice, scoped to a member instead of a place because that is what a quantity project can
     * name.
     */
    public record Cooldown(AgentId who, long retryAfter) {
    }

    /**
     * One member's outstanding trip, as the store holds it.
     *
     * <p><b>The size has to be written down.</b> A trip's SIZE is not derivable from anything else
     * the row carries — it was decided by the split against a remainder that has moved since — and
     * a reload that re-derived it would mint in ROSTER order, before {@code PartyBoard.restore} has
     * reclaimed a single hold. A party whose remainder no longer covers everybody would then hand
     * the trips to the wrong members and drop the saved holds on the floor, while the members
     * actually mid-walk carried on walking: an overshoot of several trips, past the one the
     * per-trip cap is supposed to bound the error to.
     */
    public record Trip(AgentId who, int size) {
    }

    private final ItemSpec spec;
    private final int target;

    /** Where the goods are wanted. A hint, exactly as a clearing's yard is — see {@code EnsureStore}. */
    private final Pos yard;

    private final double priority;
    private final PartyId party;
    private final Split split;

    /** The chests actually standing at the yard, learned from workers as they report in. */
    private final Set<Pos> yardChests = new LinkedHashSet<>();

    /** The latest reading per yard chest — the whole of what this project believes it has. */
    private final Map<Pos, Reading> readings = new LinkedHashMap<>();

    /** What is on offer right now. Held rather than rebuilt: the board leases items by IDENTITY. */
    private final Map<WorkKey, WorkItem> open = new LinkedHashMap<>();

    /** Items somebody is holding, so a withdrawal can never pull one out from under a worker. */
    private final Set<WorkKey> claimed = new LinkedHashSet<>();

    /** Member → the game time before which a fresh trip of THIS project is not minted for them. */
    private final Map<AgentId, Long> cooldownUntil = new LinkedHashMap<>();

    /** {@link #open} as the board sees it, rebuilt on change so an ask allocates nothing. */
    private List<WorkItem> offer = List.of();

    /**
     * The game time as of the last {@link #tick} — {@link #offerableTo} has no clock of its own
     * (the board calls it per-asker, not per-beat), and a reading stale by one host cadence is
     * nothing against a {@value #FAIL_COOLDOWN}-tick cooldown.
     */
    private long lastTick;

    public Gather(ItemSpec spec, int target, Pos yard, double priority, PartyId party, Split split) {
        this.spec = spec;
        this.target = target;
        this.yard = yard;
        this.priority = priority;
        this.party = party;
        this.split = split;
    }

    public ItemSpec spec() {
        return spec;
    }

    public int target() {
        return target;
    }

    /** Where the operator asked the goods to go. */
    public Pos yard() {
        return yard;
    }

    public PartyId party() {
        return party;
    }

    public Split split() {
        return split;
    }

    /** The chests known to stand at the yard, in the order they were learned about. */
    public List<Pos> yardChests() {
        return List.copyOf(yardChests);
    }

    /** The latest reading of each yard chest, in the order the chests were learned about. */
    public List<Reading> readings() {
        return List.copyOf(readings.values());
    }

    // ── the arithmetic ───────────────────────────────────────────────────────────────────────

    /** What the yard was last seen holding: the sum of the latest reading of each of its chests. */
    public int stored() {
        int total = 0;
        for (Reading reading : readings.values()) {
            total += reading.count();
        }
        return total;
    }

    /**
     * How much this project has already handed out, claimed or merely on offer.
     *
     * <p><b>Every live item counts, not only the claimed ones.</b> An item on offer is a stack this
     * project has already committed to fetching; counting only claimed trips would mint the same
     * stack again on every beat until somebody took it, and would hand a party of eight eight trips
     * for a job of four. It is also what makes the size fall out of the ratio within a single
     * minting pass: each trip handed out shrinks the remainder the next member is sized against.
     */
    public int inFlight() {
        int total = 0;
        for (WorkItem item : open.values()) {
            total += ((TripItem) item).size();
        }
        return total;
    }

    /** What is still wanted and unspoken-for. Never negative: an overshoot is not a debt. */
    public int remainder() {
        return Math.max(0, target - stored() - inFlight());
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
        return stored() >= target;
    }

    // ── the beat ─────────────────────────────────────────────────────────────────────────────

    /**
     * One host beat: members who left lose their trip, and whoever is free and still wanted is
     * minted one. All bookkeeping over state and the roster, which is what lets a party board keep
     * thinking with every member unloaded.
     *
     * <p><b>Minting happens here rather than in {@link #open()}</b>, whose contract is to return a
     * view of state already held: rebuilding the offer per ask would hand out a different item
     * object every time, and the board leases items from an {@code IdentityHashMap}.
     */
    @Override
    public void tick(long now) {
        lastTick = now;
        List<AgentId> members = PartyMembers.of(party);
        for (WorkKey key : List.copyOf(open.keySet())) {
            if (key instanceof WorkKey.ForMember trip && !members.contains(trip.who())) {
                withdraw(key);
            }
        }
        if (finished()) {
            withdrawAll();
            return;
        }
        mint(members, now);
        rebuildOffer();
    }

    /**
     * One trip each, to everyone free, until nothing is left to hand out.
     *
     * <p>The free count is re-read on every member rather than taken once, so the share shrinks as
     * the pass goes: a party of eight on a job of one stack gives four of them sixteen apiece and
     * the other four nothing, which is the split table's second row. A zero share ends the pass —
     * the remainder cannot grow again inside it.
     */
    private void mint(List<AgentId> members, long now) {
        for (AgentId member : members) {
            WorkKey key = new WorkKey.ForMember(WorkKey.GATHER, member);
            if (open.containsKey(key) || cooling(member, now)) {
                continue;
            }
            int size = split.tripFor(remainder(), free(members, now));
            if (size <= 0) {
                return;
            }
            open.put(key, new TripItem(size));
        }
    }

    /** Whether this member's last trip of this project failed too recently to try again. */
    private boolean cooling(AgentId member, long now) {
        Long until = cooldownUntil.get(member);
        return until != null && until > now;
    }

    /**
     * How many members hold no live item of this project and are not waiting out a failure — the
     * split's divisor. A cooling member is excluded rather than merely skipped when minting: they
     * are not about to receive a trip this beat, so counting them would shrink everyone else's
     * share against a member who is not actually there to take it.
     */
    private int free(List<AgentId> members, long now) {
        int free = 0;
        for (AgentId member : members) {
            if (!open.containsKey(new WorkKey.ForMember(WorkKey.GATHER, member))
                    && !cooling(member, now)) {
                free++;
            }
        }
        return free;
    }

    // ── outcomes ─────────────────────────────────────────────────────────────────────────────

    @Override
    public void claimed(WorkItem item) {
        keyOf(item).ifPresent(claimed::add);
    }

    /**
     * A trip belongs to the one member it was minted for, and never to a member this project is
     * pacing after a failure — see {@link #FAIL_COOLDOWN}. Without the first check the board hands
     * a cooling member somebody ELSE's trip instead of nothing, which fails that one too and
     * benches them off the whole board (live, 2026-08-24, settler {@code Di}).
     */
    @Override
    public boolean offerableTo(WorkItem item, AgentId asker) {
        if (cooling(asker, lastTick)) {
            return false;
        }
        return keyOf(item)
                .filter(key -> key instanceof WorkKey.ForMember member && member.who().equals(asker))
                .isPresent();
    }

    /**
     * A lapsed hold drops the trip outright, rather than merely un-claiming it as a clearing does.
     * A trip is named for the member who was given it and carries a size nothing else knows, so
     * putting it back on offer would leave one member's stack sitting under somebody else's name.
     * Dropping it hands the amount back to {@link #remainder()}, and the next beat re-mints it for
     * whoever is free — which is what stops the last trip stranding on one settler who died.
     */
    @Override
    public void lapsed(WorkItem item) {
        drop(item);
    }

    /**
     * A failed trip bars only the member who took it, for {@link #FAIL_COOLDOWN} — see that
     * constant for why a quantity project needs any pacing at all despite having no place to pace it
     * against. Every other member is untouched, and the project keeps minting for them next beat.
     */
    @Override
    public void failed(WorkItem item, BrainContext ctx) {
        keyOf(item).ifPresent(key -> {
            if (key instanceof WorkKey.ForMember member) {
                cooldownUntil.put(member.who(), ctx.percepts().time() + FAIL_COOLDOWN);
            }
        });
        drop(item);
    }

    private void drop(WorkItem item) {
        keyOf(item).ifPresent(key -> {
            claimed.remove(key);
            open.remove(key);
            rebuildOffer();
        });
    }

    /**
     * A trip came back: the reporter tells us where the yard's chests are and what is in them, and
     * if that satisfies the target the project closes with a line in THEIR journal.
     *
     * <p><b>The closing line has to be written here.</b> {@code Board.closeFinished} drops a
     * finished project without a word and {@code PartyBoard.tick} holds no {@link BrainContext} at
     * all, so a report is the only moment a party project ever has a worker's context — the same
     * reason {@code ClearArea} closes its box from inside a report.
     */
    @Override
    public void completed(WorkItem item, BrainContext ctx) {
        Optional<WorkKey> named = keyOf(item);
        if (named.isEmpty()) {
            return;
        }
        WorkKey key = named.get();
        claimed.remove(key);
        // Whatever the trip was, this worker has just been standing in the yard with a lid open.
        learnYard(ctx);
        readYard(ctx, ctx.percepts().time());
        open.remove(key);
        if (finished()) {
            withdrawAll();
            ctx.journal().record(Category.PROJECT, name(), closingLine());
            return;
        }
        rebuildOffer();
    }

    /**
     * Learns what a returning worker knows about the yard: every store they remember near enough to
     * the hint to BE the yard. Called from {@link #completed} because that is the one moment this
     * project holds a worker and their knowledge — the board itself never reads a mind.
     */
    private void learnYard(BrainContext ctx) {
        double radius = ctx.profile().i(ProfileAspect.STORES_FOUND_RADIUS);
        for (PoiMemory memory : ctx.knowledge().all(Store.POI)) {
            if (Store.distance(memory.anchor(), yard) <= radius) {
                yardChests.add(memory.anchor());
            }
        }
    }

    /**
     * Takes the reporter's belief about each yard chest into the ledger, keeping only the LATEST
     * belief about each — a member arriving with an hour-old memory has not seen anything newer,
     * so their reading is not an update.
     *
     * <p><b>A chest the reporter cannot remember at all reads as EMPTY, stamped now.</b> That is a
     * reading, not an erasure, and the difference is the whole rule. {@code Store.wouldNotOpen}
     * disproves a chest that has gone, so a vanished yard chest stops being remembered; erasing the
     * row instead would leave nothing for the staleness guard to compare against, and the next
     * reporter who happened to look inside it last week would write the old count straight back —
     * a phantom the project then closes satisfied over. A zero stamped at this tick outranks every
     * belief older than it, and only somebody who has actually been back can raise it again.
     *
     * <p><b>It is deliberately biased to UNDERCOUNT.</b> The same rule zeroes a perfectly VALID
     * reading when a member's memory cap has evicted the place, and that asymmetry is the point:
     * the error must always be "collect too much", never a project closing over an empty hole.
     */
    private void readYard(BrainContext ctx, long now) {
        Set<Pos> remembered = new HashSet<>();
        for (PoiMemory memory : ctx.knowledge().all(Store.POI)) {
            remembered.add(memory.anchor());
        }
        for (Pos chest : yardChests) {
            if (!remembered.contains(chest)) {
                // `now` is by construction newer than any belief anybody can be carrying.
                readings.put(chest, new Reading(chest, 0, now));
                continue;
            }
            ctx.knowledge().insideOf(chest).ifPresent(seen -> {
                Reading held = readings.get(chest);
                if (held == null || seen.seenTick() >= held.at()) {
                    readings.put(chest, new Reading(chest, seen.count(spec), seen.seenTick()));
                }
            });
        }
    }

    // ── what a member must keep hold of ──────────────────────────────────────────────────────

    /**
     * The cargo, so {@code Unburden} and {@code StowSurplus} can tell it from kit and never divert a
     * load into a personal stash mid-walk.
     *
     * <p>Sized by the LARGEST live trip because {@code reserved()} has no asker to size it per
     * member — the board publishes one list to everybody. Over-reserving costs a member carrying a
     * smaller trip nothing but a slightly later stow; under-reserving loses the load, so this errs
     * upward and counts items merely on offer as well as claimed ones.
     */
    @Override
    public List<ItemCall> reserved() {
        int largest = 0;
        for (WorkItem item : open.values()) {
            largest = Math.max(largest, ((TripItem) item).size());
        }
        return largest == 0 ? List.of() : List.of(ItemCall.need(spec, largest));
    }

    // ── durable names ────────────────────────────────────────────────────────────────────────

    @Override
    public Optional<WorkKey> keyOf(WorkItem item) {
        for (Map.Entry<WorkKey, WorkItem> entry : open.entrySet()) {
            if (entry.getValue() == item) {
                return Optional.of(entry.getKey());
            }
        }
        return Optional.empty();
    }

    @Override
    public Optional<WorkItem> itemFor(WorkKey key) {
        return Optional.ofNullable(open.get(key));
    }

    // ── the readout ──────────────────────────────────────────────────────────────────────────

    /**
     * What this project is, with no word about how far along it is — the subject every journal line
     * is filed under. Separate from {@link #describe()}, which wants both in one string: a clearing
     * that shared them once closed with {@code done (3 cleared - done) 3 cleared}.
     */
    private String name() {
        return "gather " + target + " " + spec.name() + " at " + at(yard);
    }

    @Override
    public String describe() {
        return name() + " — " + progress();
    }

    private String progress() {
        if (finished()) {
            return closingLine();
        }
        int trips = open.size();
        return stored() + "/" + target + " in the yard, " + trips
                + (trips == 1 ? " trip out" : " trips out");
    }

    private String closingLine() {
        return "done — the yard holds " + stored();
    }

    private static String at(Pos p) {
        return "(" + p.x() + ", " + p.y() + ", " + p.z() + ")";
    }

    // ── internals ────────────────────────────────────────────────────────────────────────────

    /** Drops an offer, unless somebody is out there acting on it. */
    private void withdraw(WorkKey key) {
        if (!claimed.contains(key)) {
            open.remove(key);
        }
    }

    private void withdrawAll() {
        open.keySet().removeIf(key -> !claimed.contains(key));
        rebuildOffer();
    }

    private void rebuildOffer() {
        this.offer = List.copyOf(open.values());
    }

    /**
     * One member's whole trip: go and get this much, then put it in the yard. It does not carry who
     * it is for — the {@link WorkKey.ForMember} it is filed under already says, and an item holding
     * its own name would be a second copy to keep in step.
     */
    private final class TripItem implements WorkItem {
        private final int size;

        private TripItem(int size) {
            this.size = size;
        }

        /** How much this trip was sized for, fixed at mint — see {@link Gather#inFlight()}. */
        private int size() {
            return size;
        }

        @Override
        public double priority() {
            return priority;
        }

        /**
         * Distance to the yard, mapped onto the bid scale. Horizontal distance is not worth the
         * extra arithmetic here — the fetch, which is the bulk of the walk, prices itself per asker
         * inside {@code ObtainItem}, and this only has to keep a far-off gather from outbidding
         * work underfoot.
         */
        @Override
        public double estimatedCost(BrainContext ctx) {
            double distance = Store.distance(yard, ctx.percepts().position());
            return COST_AT_RANGE * Math.min(1.0, distance / COST_RANGE);
        }

        @Override
        public Task root() {
            return new GatheringErrand(spec, size, yard);
        }

        @Override
        public String describe() {
            return "fetch " + size + " " + spec.name() + " to " + at(yard);
        }

        @Override
        public String progress(BrainContext ctx) {
            return stored() + "/" + target + " in the yard";
        }
    }

    // ── continuity ───────────────────────────────────────────────────────────────────────────

    /**
     * Everything this project is — the party store's row.
     *
     * <p><b>The item objects are not here; who owes what IS.</b> An item is exhaust, regenerable
     * from state — and the outstanding trips are exactly that state, the same way a clearing's
     * ledger is what re-mints its errands. Deriving them instead from the roster and the remainder
     * would put the members back in a different order from the holds saved beside this row; see
     * {@link Trip}.
     *
     * @param spec the {@link ItemSpec} registry name — a mod-declared spec's matcher is a lambda
     *             and cannot be written down. A name is a durable handle only for a spec somebody
     *             DECLARES at boot; a literal {@link ItemSpec#anyOf} spec has no declarer, so the
     *             row that carries this name must also carry its ids and re-register it before
     *             {@link #restore} looks it up — see {@code PartyBoardCodecs.SPEC}
     * @param split the {@link Split} registry id, for the same reason
     */
    public record State(String spec, int target, Pos yard, double priority, PartyId party,
                        String split, List<Pos> yardChests, List<Reading> readings,
                        List<Trip> trips, List<Cooldown> cooldowns) implements ProjectState {

        @Override
        public String type() {
            return "gather";
        }
    }

    /** What this project would need to carry on exactly where it left off. */
    @Override
    public State snapshot() {
        List<Trip> trips = new ArrayList<>();
        open.forEach((key, item) -> {
            if (key instanceof WorkKey.ForMember named) {
                trips.add(new Trip(named.who(), ((TripItem) item).size()));
            }
        });
        List<Cooldown> cooldowns = new ArrayList<>();
        cooldownUntil.forEach((who, until) -> cooldowns.add(new Cooldown(who, until)));
        return new State(spec.name(), target, yard, priority, party, split.id(),
                List.copyOf(yardChests), List.copyOf(readings.values()), List.copyOf(trips),
                List.copyOf(cooldowns));
    }

    /**
     * Rebuilds a saved project, puts every outstanding trip back on the member it was minted for,
     * and only then tops up whoever is free — so a lease can be handed straight back to the member
     * who held it, and nobody new is sent for goods somebody is already carrying.
     *
     * <p>Empty when no build here registers that {@link ItemSpec} — a real failure for the store to
     * report, never a row to drop quietly, exactly as an unknown {@code Clearing} id is. An unknown
     * {@link Split} id is NOT that: a policy is not identity, so it falls back to {@link EvenSplit}
     * and the party goes on fetching rather than losing the job to a removed strategy.
     */
    public static Optional<Gather> restore(State state, long now) {
        return ItemSpec.byName(state.spec()).map(spec -> {
            Gather project = new Gather(spec, state.target(), state.yard(), state.priority(),
                    state.party(), Splits.byId(state.split()).orElse(EvenSplit.INSTANCE));
            project.yardChests.addAll(state.yardChests());
            for (Reading reading : state.readings()) {
                project.readings.put(reading.chest(), reading);
            }
            for (Cooldown cooldown : state.cooldowns()) {
                project.cooldownUntil.put(cooldown.who(), cooldown.retryAfter());
            }
            for (Trip trip : state.trips()) {
                project.seed(trip.who(), trip.size());
            }
            // Sweeps a saved trip whose member has left, and mints for anyone the remainder still
            // reaches — after the seeding above, so the two can never double-count.
            project.tick(now);
            return project;
        });
    }

    /** Puts one saved trip back, exactly as it was handed out. */
    private void seed(AgentId who, int size) {
        open.put(new WorkKey.ForMember(WorkKey.GATHER, who), new TripItem(size));
    }

    /**
     * What {@link PartyProjects} and the dispatching codec both mean by {@code "gather"} —
     * registered in {@code AutarkiaMod}. By the time a row's {@code type} has dispatched here the
     * state handed in is provably a {@link State}, so an unmatched instance would be a dispatch bug
     * rather than a saved world.
     */
    public static final ProjectType TYPE = new ProjectType() {
        @Override
        public String id() {
            return "gather";
        }

        @Override
        public Optional<Gather> restore(ProjectState state, long now) {
            return state instanceof State s ? Gather.restore(s, now) : Optional.empty();
        }
    };
}
