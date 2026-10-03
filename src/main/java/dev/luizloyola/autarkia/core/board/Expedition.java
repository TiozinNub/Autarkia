package dev.luizloyola.autarkia.core.board;

import dev.luizloyola.anima.core.agent.AgentId;
import dev.luizloyola.anima.core.brain.BrainContext;
import dev.luizloyola.anima.core.brain.board.WorkItem;
import dev.luizloyola.anima.core.brain.history.Deed;
import dev.luizloyola.anima.core.brain.knowledge.PoiKind;
import dev.luizloyola.anima.core.brain.sense.BeingId;
import dev.luizloyola.anima.core.brain.sense.Pos;
import dev.luizloyola.anima.core.brain.task.Method;
import dev.luizloyola.anima.core.brain.task.ObtainItem;
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
 * <p>One trip at a time, and the expedition is over when it comes home. A need still short after
 * it is priced out again and posts the next.
 *
 * <p><b>Others may go along</b> (Luiz, 2026-10-02) when the haul is more than the one leading can
 * carry: the leader waits {@link #MUSTER_TICKS} at HOME, and each member who takes the company
 * offer meanwhile follows it out ({@link TravelWith}) and brings home a share of its own.
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

    /** How long the one leading waits at HOME for company — the HOME search's gathering wait *(call)*. */
    public static final int MUSTER_TICKS = 600;

    /**
     * How long a need with no way at all must go on failing before anybody goes out for it: a way
     * can be missing for a while — a patch resting, every known tree struck — without being gone
     * *(call)*.
     */
    public static final long SEARCH_PATIENCE = 4800;

    /** Legs a first search walks, and the most any does: one more each time one comes back empty (Luiz). */
    public static final int SEARCH_LEGS = 4;
    public static final int MOST_SEARCH_LEGS = 8;

    /** Ticks a companion whose share failed sits the company offer out — {@code Gather}'s. */
    public static final int COMPANY_COOLDOWN = Gather.FAIL_COOLDOWN;

    /**
     * One priced-out item the haul is for.
     *
     * @param what    the item's own description — its name across the restarts it fails through
     * @param pursued what it was crafting, which is what lets the trip seek the resource at all
     * @param seen    the last time it failed
     * @param since   when it first failed, for {@link #SEARCH_PATIENCE}
     * @param lost    it failed with no way at all, rather than priced out
     */
    public record Need(AgentId who, String what, int count, double priority, Set<String> pursued, long seen,
                       boolean standing, long since, boolean lost) {
        public Need(AgentId who, String what, int count, double priority, Set<String> pursued, long seen) {
            this(who, what, count, priority, pursued, seen, false);
        }

        public Need(AgentId who, String what, int count, double priority, Set<String> pursued, long seen,
                    boolean standing) {
            this(who, what, count, priority, pursued, seen, standing, seen, false);
        }

        /** Whether it counts yet: a lost need only once it has gone on failing past the patience. */
        boolean active() {
            return !lost || standing || seen - since >= SEARCH_PATIENCE;
        }
    }

    /** A search out: who, which way, how many legs. */
    public record Search(AgentId who, int heading, int legs) {
    }

    /** The searches: the one out, how far the next goes, its pacing, the headings already tried. */
    public record Searching(List<Search> out, int legs, int failures, long retryAfter, List<Integer> tried) {
        public static final Searching NONE = new Searching(List.of(), SEARCH_LEGS, 0, 0L, List.of());
    }

    /** A trip out, with who took it — written down because nothing re-derives its size. */
    public record Trip(AgentId who, int size) {
    }

    /** One member sitting the company offer out after a failed share. */
    public record Cooldown(AgentId who, long retryAfter) {
    }

    private final ItemSpec resource;
    private final PartyId party;
    private final Split split;
    private final Map<String, Need> needs = new LinkedHashMap<>();

    private TripItem trip;
    /** Whether a hold came back for the trip after a restart — see {@link #holdsRestored}. */
    private boolean tripHeld;
    private final Map<AgentId, CompanyItem> company = new LinkedHashMap<>();
    private final Set<AgentId> companyHeld = new java.util.HashSet<>();
    /** Until when the company offer stands: the leader's muster. Zero before anybody leads. */
    private long companyUntil;
    private final Map<AgentId, Long> cooldownUntil = new LinkedHashMap<>();
    /** Whether each member knew a way, as of the beat it was asked — a memo, never saved. */
    private final Map<AgentId, Long> wayCheckedAt = new java.util.HashMap<>();
    private final Map<AgentId, Boolean> knowsWay = new java.util.HashMap<>();
    private int failures;
    private long retryAfter;
    private boolean done;
    private long lastTick;

    private SearchItem search;
    private boolean searchHeld;
    private int searchLegs = SEARCH_LEGS;
    private int searchFailures;
    private long searchRetryAfter;
    private final List<Integer> tried = new ArrayList<>();

    private final OfferItem offer = new OfferItem();
    private final CompanyOffer companyOffer = new CompanyOffer();
    private final SearchOffer searchOffer = new SearchOffer();
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
        need(new Need(who, what, count, priority, Set.copyOf(pursued), now, false), now);
    }

    /**
     * A need as given: a {@code standing} one — an operator's order, which never fails again to
     * say it is still wanted — does not lapse.
     */
    public void need(Need need, long now) {
        String key = need.who() + "|" + need.what();
        Need was = needs.get(key);
        if (was != null && was.since() < need.since()) {
            need = new Need(need.who(), need.what(), need.count(), need.priority(), need.pursued(), need.seen(),
                    need.standing(), was.since(), need.lost());
        }
        needs.put(key, need);
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
            if (need.active()) {
                total += need.count();
            }
        }
        return HAUL_PER_NEED * total;
    }

    /** What is left of the haul once the trip and every companion's share are counted. */
    private int unclaimed() {
        int left = haul() - (trip == null ? 0 : trip.size());
        for (CompanyItem along : company.values()) {
            left -= along.size();
        }
        return Math.max(0, left);
    }

    private Set<String> pursued() {
        Set<String> all = new TreeSet<>();
        for (Need need : needs.values()) {
            all.addAll(need.pursued());
        }
        return all;
    }

    private boolean anyActive() {
        return needs.values().stream().anyMatch(Need::active);
    }

    /** Whether a need with no way at all has waited long enough to send somebody looking. */
    private boolean worthSearching() {
        return needs.values().stream().anyMatch(need -> need.lost() && need.active())
                && SourceKinds.of(resource).isPresent();
    }

    // ── the beat ─────────────────────────────────────────────────────────────────────────────

    @Override
    public double priority() {
        double best = 0;
        for (Need need : needs.values()) {
            if (need.active()) {
                best = Math.max(best, need.priority());
            }
        }
        return best;
    }

    @Override
    public void tick(long now) {
        lastTick = now;
        if (trip != null || search != null || !company.isEmpty()) {
            // Nothing fails at HOME while its only member is out looking or fetching, so a need
            // does not age then: a lone settler's search outlived its needs and started over.
            needs.replaceAll((key, need) -> new Need(need.who(), need.what(), need.count(), need.priority(),
                    need.pursued(), Math.max(need.seen(), now), need.standing(), need.since(), need.lost()));
        }
        needs.values().removeIf(need -> !need.standing() && need.seen() + NEED_LAPSE < now);
        rebuildOffer();
    }

    @Override
    public boolean finished() {
        return company.isEmpty() && search == null && (done || (needs.isEmpty() && trip == null));
    }

    @Override
    public List<WorkItem> open() {
        return open;
    }

    private void rebuildOffer() {
        List<WorkItem> items = new ArrayList<>();
        if (trip != null) {
            items.add(trip);
            if (!done && lastTick < companyUntil && unclaimed() > 0) {
                items.add(companyOffer);
            }
        } else if (search != null) {
            items.add(search);
        } else if (anyActive() && !done) {
            items.add(offer);
            if (worthSearching() && lastTick >= searchRetryAfter) {
                items.add(searchOffer);
            }
        }
        items.addAll(company.values());
        open = List.copyOf(items);
    }

    // ── what one body takes ──────────────────────────────────────────────────────────────────

    @Override
    public boolean owns(WorkItem item) {
        return item == offer || item == companyOffer || item == searchOffer
                || (item instanceof SearchItem looking && looking.owner() == this)
                || (item instanceof TripItem taken && taken.owner() == this)
                || (item instanceof CompanyItem along && along.owner() == this);
    }

    @Override
    public boolean offerableTo(WorkItem item, AgentId asker, BrainContext ctx) {
        if (ctx.depot().isEmpty()) {
            return false;
        }
        if (item == offer) {
            return trip == null && lastTick >= retryAfter && split.tripFor(haul(), resource, ctx) > 0
                    && knowsTheWay(asker, ctx);
        }
        if (item == companyOffer) {
            return trip != null && !trip.who().equals(asker) && !company.containsKey(asker)
                    && cooldownUntil.getOrDefault(asker, 0L) <= lastTick && lastTick < companyUntil
                    && split.tripFor(unclaimed(), resource, ctx) > 0;
        }
        if (item instanceof CompanyItem along) {
            return along.who().equals(asker);
        }
        if (item == searchOffer) {
            return trip == null && search == null && lastTick >= searchRetryAfter && !knowsTheWay(asker, ctx);
        }
        if (item instanceof SearchItem looking) {
            return looking.who().equals(asker);
        }
        return item == trip && trip.who().equals(asker);
    }

    @Override
    public WorkItem realise(WorkItem offered, AgentId asker, BrainContext ctx) {
        if (offered == companyOffer) {
            int share = split.tripFor(unclaimed(), resource, ctx);
            return share <= 0 ? offered : new CompanyItem(asker, share);
        }
        if (offered == searchOffer) {
            return new SearchItem(asker, headingFor(ctx), searchLegs);
        }
        if (offered != offer) {
            return offered;
        }
        int size = split.tripFor(haul(), resource, ctx);
        // Away from HOME — a searcher who has just found it — nobody could come along.
        boolean away = ctx.depot().map(site -> !site.holds(ctx.percepts().position())).orElse(false);
        return size <= 0 ? offered
                : new TripItem(asker, size, pursued(), size < haul() && !away ? MUSTER_TICKS : 0);
    }

    @Override
    public void claimed(WorkItem item, AgentId who) {
        if (item instanceof TripItem taken && owns(taken)) {
            if (trip != taken) {
                companyUntil = lastTick + taken.muster();
            }
            trip = taken;
            tripHeld = true;
            rebuildOffer();
        } else if (item instanceof CompanyItem along && owns(along)) {
            company.put(along.who(), along);
            companyHeld.add(along.who());
            rebuildOffer();
        } else if (item instanceof SearchItem looking && owns(looking)) {
            if (search != looking && !tried.contains(looking.heading())) {
                tried.add(looking.heading());
            }
            search = looking;
            searchHeld = true;
            rebuildOffer();
        }
    }

    /**
     * Which way a search goes: toward the nearest glimpse of the kind the searcher has, else the
     * first heading no search has tried, starting from one this party always starts from.
     */
    private int headingFor(BrainContext ctx) {
        Pos here = ctx.percepts().position();
        Optional<PoiKind> kind = SourceKinds.of(resource);
        if (kind.isPresent()) {
            Optional<dev.luizloyola.anima.core.brain.knowledge.Sighting> seen =
                    ctx.knowledge().nearestGlimpse(kind.get(), here);
            if (seen.isPresent()) {
                return SeekSource.headingTo(here, seen.get().at());
            }
        }
        int start = Math.floorMod(party.hashCode(), 8);
        for (int i = 0; i < 8; i++) {
            int heading = (start + i) % 8;
            if (!tried.contains(heading)) {
                return heading;
            }
        }
        return start;
    }

    /** A search came back: what the searcher knows now says whether it found it. */
    private void searched(SearchItem looking, BrainContext ctx, boolean found) {
        search = null;
        if (found) {
            searchLegs = SEARCH_LEGS;
            searchFailures = 0;
            tried.clear();
            wayCheckedAt.remove(looking.who());
            ctx.journal().record(Category.PROJECT, describe(), "found it to the " + SeekSource.HEADINGS[looking.heading()]);
        } else {
            searchFailures++;
            searchLegs = Math.min(MOST_SEARCH_LEGS, searchLegs + 1);
            searchRetryAfter = ctx.percepts().time() + cooldownAfter(searchFailures);
            if (tried.size() >= 8) {
                tried.clear();
            }
            ctx.journal().record(Category.PROJECT, describe(), "found nothing to the "
                    + SeekSource.HEADINGS[looking.heading()] + "; the next search goes " + searchLegs + " legs");
        }
        rebuildOffer();
    }

    /** Whether {@code ctx}'s body knows a way to {@code resource} within {@link #REACH}. */
    static boolean knowsAWay(ItemSpec resource, Set<String> pursued, BrainContext ctx) {
        ObtainItem obtain = new ObtainItem(resource, 1, pursued, ObtainItem.Sources.NOT_STORES);
        for (Method way : obtain.methods()) {
            if (way.applicable(ctx) && way.estimateCost(ctx) <= REACH) {
                return true;
            }
        }
        return false;
    }

    @Override
    public void completed(WorkItem item, BrainContext ctx) {
        if (item instanceof SearchItem looking && looking == search) {
            searched(looking, ctx, knowsAWay(resource, pursued(), ctx));
            return;
        }
        if (item instanceof CompanyItem along && company.get(along.who()) == along) {
            company.remove(along.who());
            rebuildOffer();
            return;
        }
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
        if (item instanceof SearchItem looking && looking == search) {
            searched(looking, ctx, false);
            return;
        }
        if (item instanceof CompanyItem along && company.get(along.who()) == along) {
            company.remove(along.who());
            cooldownUntil.put(along.who(), ctx.percepts().time() + COMPANY_COOLDOWN);
            rebuildOffer();
            return;
        }
        if (item != trip) {
            return;
        }
        trip = null;
        failures++;
        retryAfter = ctx.percepts().time() + cooldownAfter(failures);
        rebuildOffer();
    }

    /**
     * Whether {@code asker} knows a source within {@link #REACH} — only one who does can lead; the
     * rest learn the way by going along. Once a beat per member: the board asks on every tick.
     */
    private boolean knowsTheWay(AgentId asker, BrainContext ctx) {
        Long at = wayCheckedAt.get(asker);
        if (at == null || at != lastTick) {
            wayCheckedAt.put(asker, lastTick);
            knowsWay.put(asker, knowsAWay(resource, pursued(), ctx));
        }
        return knowsWay.getOrDefault(asker, false);
    }

    static long cooldownAfter(int failures) {
        return (long) FAIL_COOLDOWN << Math.min(MOST_DOUBLINGS, Math.max(0, failures - 1));
    }

    @Override
    public void lapsed(WorkItem item) {
        if (item == trip) {
            trip = null;
        } else if (item == search) {
            search = null;
        } else if (item instanceof CompanyItem along && company.get(along.who()) == along) {
            company.remove(along.who());
        }
        rebuildOffer();
    }

    @Override
    public List<ItemCall> reserved() {
        int largest = trip == null ? 0 : trip.size();
        for (CompanyItem along : company.values()) {
            largest = Math.max(largest, along.size());
        }
        return largest == 0 ? List.of() : List.of(ItemCall.need(resource, largest));
    }

    // ── durable names ────────────────────────────────────────────────────────────────────────

    @Override
    public Optional<WorkKey> keyOf(WorkItem item) {
        if (item instanceof SearchItem looking && looking == search) {
            return Optional.of(new WorkKey.ForMember(WorkKey.EXPEDITION_SEARCH, looking.who()));
        }
        if (item instanceof CompanyItem along && company.get(along.who()) == along) {
            return Optional.of(new WorkKey.ForMember(WorkKey.EXPEDITION_COMPANY, along.who()));
        }
        return item == trip && trip != null
                ? Optional.of(new WorkKey.ForMember(WorkKey.EXPEDITION, trip.who())) : Optional.empty();
    }

    @Override
    public Optional<WorkItem> itemFor(WorkKey key) {
        if (key instanceof WorkKey.ForMember member && WorkKey.EXPEDITION_COMPANY.equals(member.flavour())) {
            return Optional.ofNullable(company.get(member.who()));
        }
        if (key instanceof WorkKey.ForMember member && WorkKey.EXPEDITION_SEARCH.equals(member.flavour())) {
            return search != null && search.who().equals(member.who()) ? Optional.of(search) : Optional.empty();
        }
        return keyOf(trip).filter(key::equals).map(found -> trip);
    }

    /** A trip or share saved without its hold is nobody's: dropped, so the work can go again. */
    @Override
    public void holdsRestored() {
        if (trip != null && !tripHeld) {
            trip = null;
        }
        company.keySet().removeIf(who -> !companyHeld.contains(who));
        if (search != null && !searchHeld) {
            search = null;
        }
        rebuildOffer();
    }

    // ── the readout ──────────────────────────────────────────────────────────────────────────

    @Override
    public String describe() {
        int count = needs.size();
        return "expedition for " + (haul() == 0 ? "" : haul() + " ") + resource.name() + " — " + count
                + (count == 1 ? " need" : " needs") + (trip == null ? "" : ", a trip out")
                + (company.isEmpty() ? "" : ", " + company.size() + " along")
                + (search == null ? "" : ", searching")
                + (haul() == 0 && count > 0 ? ", not yet" : "");
    }

    // ── continuity ───────────────────────────────────────────────────────────────────────────

    public record State(String resource, PartyId party, String split, List<Need> needs, List<Trip> trip,
                        int failures, long retryAfter, long lastTick, List<Trip> company, long companyUntil,
                        List<Cooldown> cooldowns, Searching searching) implements ProjectState {
        /** A state saved before company was. */
        public State(String resource, PartyId party, String split, List<Need> needs, List<Trip> trip,
                     int failures, long retryAfter, long lastTick) {
            this(resource, party, split, needs, trip, failures, retryAfter, lastTick, List.of(), 0L, List.of());
        }

        /** A state saved before searches were. */
        public State(String resource, PartyId party, String split, List<Need> needs, List<Trip> trip,
                     int failures, long retryAfter, long lastTick, List<Trip> company, long companyUntil,
                     List<Cooldown> cooldowns) {
            this(resource, party, split, needs, trip, failures, retryAfter, lastTick, company, companyUntil,
                    cooldowns, Searching.NONE);
        }

        @Override
        public String type() {
            return "expedition";
        }
    }

    @Override
    public State snapshot() {
        List<Trip> out = trip == null ? List.of() : List.of(new Trip(trip.who(), trip.size()));
        List<Trip> along = new ArrayList<>();
        company.values().forEach(share -> along.add(new Trip(share.who(), share.size())));
        List<Cooldown> cooling = new ArrayList<>();
        cooldownUntil.forEach((who, until) -> cooling.add(new Cooldown(who, until)));
        List<Search> looking = search == null ? List.of()
                : List.of(new Search(search.who(), search.heading(), search.legs()));
        return new State(resource.name(), party, split.id(), List.copyOf(needs.values()), out,
                failures, retryAfter, lastTick, List.copyOf(along), companyUntil, List.copyOf(cooling),
                new Searching(looking, searchLegs, searchFailures, searchRetryAfter, List.copyOf(tried)));
    }

    public static Optional<Expedition> restore(State state, long now) {
        return ItemSpec.byName(state.resource()).map(resource -> {
            Expedition project = new Expedition(resource, state.party(),
                    Splits.byId(state.split()).orElse(CarrySplit.INSTANCE));
            for (Need need : state.needs()) {
                project.needs.put(need.who() + "|" + need.what(), need);
            }
            for (Trip saved : state.trip()) {
                project.trip = project.new TripItem(saved.who(), saved.size(), project.pursued(), 0);
            }
            for (Trip saved : state.company()) {
                project.company.put(saved.who(), project.new CompanyItem(saved.who(), saved.size()));
            }
            project.companyUntil = state.companyUntil();
            Searching searching = state.searching();
            for (Search saved : searching.out()) {
                project.search = project.new SearchItem(saved.who(), saved.heading(), saved.legs());
            }
            project.searchLegs = searching.legs();
            project.searchFailures = searching.failures();
            project.searchRetryAfter = searching.retryAfter();
            project.tried.addAll(searching.tried());
            for (Cooldown cooling : state.cooldowns()) {
                project.cooldownUntil.put(cooling.who(), cooling.retryAfter());
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

    /** Going out to look: whoever takes it gets a {@link SearchItem} of their own. */
    private final class SearchOffer extends Item {
        @Override
        public Task root() {
            throw new IllegalStateException("an expedition's search offer is replaced by a search before it is run");
        }

        @Override
        public Deed doing() {
            return Deed.of(WorkDoings.EXPLORING);
        }

        @Override
        public String describe() {
            return "look for " + resource.name() + " further off";
        }
    }

    private final class SearchItem extends Item {
        private final AgentId who;
        private final int heading;
        private final int legs;

        private SearchItem(AgentId who, int heading, int legs) {
            this.who = who;
            this.heading = heading;
            this.legs = legs;
        }

        AgentId who() {
            return who;
        }

        int heading() {
            return heading;
        }

        int legs() {
            return legs;
        }

        Expedition owner() {
            return Expedition.this;
        }

        @Override
        public Task root() {
            return new SearchErrand(new SeekSource(SourceKinds.of(resource).orElseThrow(), resource, pursued(),
                    heading, legs));
        }

        @Override
        public Deed doing() {
            return Deed.of(WorkDoings.EXPLORING);
        }

        @Override
        public String describe() {
            return "look for " + resource.name() + " to the " + SeekSource.HEADINGS[heading];
        }
    }

    /**
     * Going along: whoever takes it gets a {@link CompanyItem} of their own. Free to bid: it stands
     * only through a muster, when the haul needs more than one pack, and at the trip's price a
     * companion lost to every errand at HOME and the leader waited for nobody (2026-10-03) *(call)*.
     */
    private final class CompanyOffer extends Item {
        @Override
        public double estimatedCost(BrainContext ctx) {
            return 0.0;
        }

        @Override
        public Task root() {
            throw new IllegalStateException("an expedition's company offer is replaced by a share before it is run");
        }

        @Override
        public String describe() {
            return "go along for " + unclaimed() + " " + resource.name();
        }
    }

    private final class CompanyItem extends Item {
        private final AgentId who;
        private final int size;

        @Override
        public double estimatedCost(BrainContext ctx) {
            return 0.0;
        }

        private CompanyItem(AgentId who, int size) {
            this.who = who;
            this.size = size;
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
            return new ExpeditionErrand(resource, size, pursued(),
                    trip == null ? null : BeingId.of(trip.who()), 0);
        }

        @Override
        public String describe() {
            return "go along for " + size + " " + resource.name();
        }
    }

    private final class TripItem extends Item {
        private final AgentId who;
        private final int size;
        private final Set<String> pursued;
        /** The wait for company it was taken with — zero when one pack holds the haul. */
        private final int muster;

        private TripItem(AgentId who, int size, Set<String> pursued, int muster) {
            this.who = who;
            this.size = size;
            this.pursued = Set.copyOf(pursued);
            this.muster = muster;
        }

        int muster() {
            return muster;
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

        /** The muster left, so a trip resumed after the wait does not wait again. */
        @Override
        public Task root() {
            int wait = companyUntil == 0 ? muster : (int) Math.max(0, companyUntil - lastTick);
            return new ExpeditionErrand(resource, size, pursued, null, wait);
        }

        @Override
        public String describe() {
            return "fetch " + size + " " + resource.name() + " from far off";
        }
    }
}
