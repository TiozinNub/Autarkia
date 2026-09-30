package dev.luizloyola.autarkia.core.board;

import dev.luizloyola.anima.core.agent.AgentId;
import dev.luizloyola.anima.core.brain.BrainContext;
import dev.luizloyola.anima.core.brain.board.WorkItem;
import dev.luizloyola.anima.core.brain.history.Deed;
import dev.luizloyola.anima.core.brain.sense.Pos;
import dev.luizloyola.anima.core.brain.sense.BeingId;
import dev.luizloyola.anima.core.brain.task.Follow;
import dev.luizloyola.anima.core.brain.task.GoTo;
import dev.luizloyola.anima.core.brain.task.LookRound;
import dev.luizloyola.anima.core.brain.task.Task;
import dev.luizloyola.anima.core.brain.task.WaitForCompany;
import dev.luizloyola.anima.core.log.Category;
import dev.luizloyola.anima.core.social.PartyId;
import dev.luizloyola.anima.core.store.Store;
import dev.luizloyola.autarkia.core.direction.HomeJudge;
import dev.luizloyola.autarkia.core.direction.HomeJudge.Candidate;
import dev.luizloyola.autarkia.core.direction.HomeKnob;
import dev.luizloyola.autarkia.core.direction.HomeLooking;
import dev.luizloyola.autarkia.core.direction.HomeSearch;
import dev.luizloyola.autarkia.core.direction.HomeSearch.Phase;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * A party with no HOME goes looking for one: a scout looks round, walks a leg the way that looks
 * best, and looks again, until a place clears the bar or patience runs out and it goes back to the
 * best it saw and claims it (docs/superpowers/specs/2026-09-25-home-search-design.md). The search is
 * {@link HomeSearch}; this is its place on the board.
 *
 * <p><b>One item at a time, one taker.</b> Each step — look, walk, settle — is one item; the next is
 * offered when it completes, and whoever took the last is the scout. The steps are items rather than
 * one long task because a step's result is the project's: a look is judged in {@link #completed},
 * the one moment a party project holds a member's context, and the search's state lasts here.
 *
 * <p>The ground and the claim are the world's, read through the {@link HomeLooking} the mod layer
 * installs.
 */
public final class Explore implements PartyProject {

    /** Ticks one member sits this project out after failing a look or a claim. */
    public static final int FAIL_COOLDOWN = 600;

    /**
     * Ticks a step may go untaken before somebody other than the scout may take it — the scout left
     * the party, died, or froze in an unloaded chunk.
     */
    public static final int SCOUT_LAPSE = 1200;

    private static volatile @Nullable HomeLooking looking;

    private final PartyId party;
    private final double priority;
    private final HomeSearch search;
    private @Nullable AgentId scout;
    private final Map<AgentId, Long> cooldownUntil = new LinkedHashMap<>();
    private long lastTick;

    /** Each companion's own item, minted when they take the offer to keep with the scout. */
    private final Map<AgentId, Accompany> accompanying = new LinkedHashMap<>();

    /** Whether the party has gathered at the current stop, so the scout may look round. */
    private boolean gathered;

    /** When the current step was last offered or taken: how long the scout has left it. */
    private long stepSince;

    /** The one step on offer, rebuilt as the search moves. The board leases by identity. */
    private Step current;

    /** The offer to keep with the scout, one object for everybody; see {@link #realise}. */
    private final AccompanyOffer offer = new AccompanyOffer();

    public Explore(PartyId party, double priority) {
        this(party, priority, new HomeSearch());
    }

    private Explore(PartyId party, double priority, HomeSearch search) {
        this.party = Objects.requireNonNull(party, "party");
        this.priority = priority;
        this.search = search;
        this.current = step();
    }

    /** The step the search is at: at a stop, the wait for the party comes before the look. */
    private Step step() {
        boolean waiting = search.phase() == Phase.LOOK && !gathered && !accompanying.isEmpty();
        return new Step(search.phase(), waiting);
    }

    /** How the world is read. Installed once by the mod layer. */
    public static void install(@Nullable HomeLooking installed) {
        looking = installed;
    }

    public PartyId party() {
        return party;
    }

    public HomeSearch search() {
        return search;
    }

    public @Nullable AgentId scout() {
        return scout;
    }

    @Override
    public double priority() {
        return priority;
    }

    /** The step, and while there is a scout to keep with, the offer to keep with it. */
    @Override
    public List<WorkItem> open() {
        if (!searching()) {
            return List.of();
        }
        return scout == null ? List.of(current) : List.of(current, offer);
    }

    /** A companion's item is on offer to nobody; it is minted for its taker. */
    @Override
    public boolean owns(WorkItem item) {
        return item == current || item == offer
                || (item instanceof Accompany accompany && accompany.owner() == this);
    }

    @Override
    public WorkItem realise(WorkItem item, AgentId asker, BrainContext ctx) {
        if (item != offer) {
            return item;
        }
        Accompany own = accompanying.get(asker);
        return own != null ? own : new Accompany(asker);
    }

    private boolean searching() {
        return switch (search.phase()) {
            case LOOK, WALK, SETTLE -> true;
            case DONE, STRANDED -> false;
        };
    }

    /**
     * HOME is claimed. A stranded search is not finished: it stays on the board, offering nothing,
     * so the line that posted it does not post it again every beat.
     */
    @Override
    public boolean finished() {
        return search.phase() == Phase.DONE;
    }

    @Override
    public void tick(long now) {
        lastTick = now;
    }

    /**
     * The step goes to the scout, or to anybody once the scout has left it a while; the offer to keep
     * with the scout goes to everybody else.
     */
    @Override
    public boolean offerableTo(WorkItem item, AgentId asker, BrainContext ctx) {
        Long until = cooldownUntil.get(asker);
        if (until != null && until > lastTick) {
            return false;
        }
        if (item == offer) {
            return scout != null && !asker.equals(scout);
        }
        if (item instanceof Accompany accompany) {
            return accompany.who().equals(asker);
        }
        return scout == null || asker.equals(scout) || lastTick - stepSince > SCOUT_LAPSE;
    }

    @Override
    public void claimed(WorkItem item, AgentId who) {
        if (item == current) {
            if (!who.equals(scout)) {
                accompanying.remove(who); // a companion who takes over the search leads it
            }
            scout = who;
            stepSince = lastTick;
        } else if (item instanceof Accompany accompany) {
            accompanying.put(who, accompany);
        }
    }

    @Override
    public void lapsed(WorkItem item) {
        if (item == current) {
            stepSince = lastTick;
        } else if (item instanceof Accompany accompany) {
            accompanying.remove(accompany.who());
        }
    }

    @Override
    public void completed(WorkItem item, BrainContext ctx) {
        if (item instanceof Accompany accompany) {
            accompanying.remove(accompany.who());
            return;
        }
        if (item != current) {
            return;
        }
        stepSince = lastTick;
        if (current.waiting) {
            gathered = true;
            current = step();
            return;
        }
        HomeLooking world = looking;
        Pos at = ctx.percepts().position();
        switch (search.phase()) {
            case LOOK -> {
                Optional<HomeLooking.Look> look = world == null ? Optional.empty()
                        : world.look(at, party, ctx.knowledge());
                if (look.isEmpty()) {
                    journal(ctx, "could not read the ground at " + at(at));
                    return; // the same look is offered again
                }
                journal(ctx, search.looked(at, look.get(), HomeJudge.Table.configured(),
                        HomeSearch.Terms.configured(), ctx.random()));
                gathered = false;
            }
            case WALK -> search.arrived();
            case SETTLE -> {
                Candidate best = search.best();
                if (best != null && world != null && world.claim(party, best, ctx.knowledge())) {
                    search.claimed();
                    journal(ctx, "settled at " + at(search.yard()) + ": " + breakdown(best));
                } else {
                    search.claimRefused();
                    journal(ctx, "the plot at " + at(search.yard()) + " was refused on a second look");
                }
            }
            case DONE, STRANDED -> {
            }
        }
        current = step();
    }

    @Override
    public void failed(WorkItem item, AgentId who, BrainContext ctx) {
        if (item instanceof Accompany accompany) {
            accompanying.remove(accompany.who());
            cooldownUntil.put(who, ctx.percepts().time() + FAIL_COOLDOWN);
            return;
        }
        if (item != current) {
            return;
        }
        if (search.phase() == Phase.WALK) {
            journal(ctx, search.failedLeg(ctx.percepts().position()));
            current = step();
            return;
        }
        cooldownUntil.put(who, ctx.percepts().time() + FAIL_COOLDOWN);
    }

    @Override
    public void failed(WorkItem item, BrainContext ctx) {
    }

    @Override
    public ProjectState snapshot() {
        List<Gather.Cooldown> cooldowns = new ArrayList<>();
        cooldownUntil.forEach((who, until) -> cooldowns.add(new Gather.Cooldown(who, until)));
        return new State(party, priority, search.snapshot(), Optional.ofNullable(scout),
                List.copyOf(cooldowns), lastTick, List.copyOf(accompanying.keySet()), gathered,
                stepSince);
    }

    /** The scout's own step, named by the scout: nobody else is handed it back after a restart. */
    @Override
    public Optional<WorkKey> keyOf(WorkItem item) {
        if (item instanceof Accompany accompany && accompanying.get(accompany.who()) == item) {
            return Optional.of(new WorkKey.ForMember(WorkKey.ACCOMPANY, accompany.who()));
        }
        return item == current && scout != null && searching()
                ? Optional.of(new WorkKey.ForMember(WorkKey.EXPLORE, scout))
                : Optional.empty();
    }

    @Override
    public Optional<WorkItem> itemFor(WorkKey key) {
        if (!searching() || !(key instanceof WorkKey.ForMember member)) {
            return Optional.empty();
        }
        if (member.flavour().equals(WorkKey.ACCOMPANY)) {
            return Optional.ofNullable(accompanying.get(member.who()));
        }
        return scout != null && key.equals(new WorkKey.ForMember(WorkKey.EXPLORE, scout))
                ? Optional.of(current)
                : Optional.empty();
    }

    @Override
    public String describe() {
        Candidate best = search.best();
        String state = switch (search.phase()) {
            case LOOK -> "looking round";
            case WALK -> "walking to " + at(search.legEnd());
            case SETTLE -> "going to settle at " + at(search.yard());
            case DONE -> "settled at " + at(search.yard());
            case STRANDED -> "found nowhere to live";
        };
        return "explore for a HOME — " + search.legs() + " legs, "
                + (best == null ? "no plot yet" : "best worth " + Math.round(best.value()))
                + ", " + state;
    }

    private void journal(BrainContext ctx, String line) {
        ctx.journal().record(Category.PROJECT, "explore", line);
    }

    private static String breakdown(Candidate plot) {
        List<String> parts = new ArrayList<>();
        for (HomeJudge.Line line : plot.lines()) {
            parts.add(line.want().key() + " " + (Double.isInfinite(line.measure()) ? "-"
                    : String.valueOf(Math.round(line.measure()))));
        }
        return String.join(", ", parts) + " — worth " + Math.round(plot.value());
    }

    private static String at(@Nullable Pos p) {
        return p == null ? "-" : "(" + p.x() + ", " + p.y() + ", " + p.z() + ")";
    }

    /** Where the scout is to be found next: its leg's end, the plot, or where it stands to look. */
    private @Nullable Pos meeting() {
        return switch (search.phase()) {
            case WALK -> search.legEnd();
            case SETTLE -> search.yard();
            default -> search.lastStop();
        };
    }

    /** One step of the search: wait for the party, look round, walk the leg, or go and settle. */
    private final class Step implements WorkItem {
        private final Phase phase;
        private final boolean waiting;

        private Step(Phase phase, boolean waiting) {
            this.phase = phase;
            this.waiting = waiting;
        }

        @Override
        public boolean buildsOnTheWay() {
            return phase != Phase.LOOK;
        }

        @Override
        public double priority() {
            return priority;
        }

        @Override
        public double estimatedCost(BrainContext ctx) {
            Pos to = target();
            if (to == null) {
                return 0;
            }
            return SetUp.COST_AT_RANGE
                    * Math.min(1.0, Store.distance(to, ctx.percepts().position()) / SetUp.COST_RANGE);
        }

        @Override
        public Task root() {
            if (waiting) {
                Set<BeingId> whom = new LinkedHashSet<>();
                accompanying.keySet().forEach(who -> whom.add(BeingId.of(who)));
                return new WaitForCompany(whom, HomeKnob.GATHER_RADIUS.i(), HomeKnob.GATHER_WAIT.i());
            }
            Pos to = target();
            return to == null ? new LookRound() : new GoTo(to.x(), to.y(), to.z());
        }

        private @Nullable Pos target() {
            return switch (phase) {
                case WALK -> search.legEnd();
                case SETTLE -> search.yard();
                default -> null;
            };
        }

        @Override
        public Deed doing() {
            return Deed.of(WorkDoings.EXPLORING);
        }

        @Override
        public String describe() {
            if (waiting) {
                return "wait for the party to catch up";
            }
            return switch (phase) {
                case LOOK -> "look round for a place to live";
                case WALK -> "walk on to " + at(search.legEnd());
                case SETTLE -> "go and settle at " + at(search.yard());
                default -> "explore";
            };
        }
    }

    /** The offer to keep with the scout; whoever takes it gets an {@link Accompany} of their own. */
    private final class AccompanyOffer implements WorkItem {

        @Override
        public double priority() {
            return priority;
        }

        @Override
        public Task root() {
            return new Follow(BeingId.of(Objects.requireNonNull(scout, "scout")), meeting());
        }

        @Override
        public Deed doing() {
            return Deed.of(WorkDoings.EXPLORING);
        }

        @Override
        public String describe() {
            return "keep with the scout";
        }
    }

    /** One companion's keeping with the scout (decision 2: the party travels together). */
    private final class Accompany implements WorkItem {
        private final AgentId who;

        private Accompany(AgentId who) {
            this.who = who;
        }

        AgentId who() {
            return who;
        }

        Explore owner() {
            return Explore.this;
        }

        @Override
        public double priority() {
            return priority;
        }

        @Override
        public Task root() {
            return new Follow(BeingId.of(Objects.requireNonNull(scout, "scout")), meeting());
        }

        @Override
        public Deed doing() {
            return Deed.of(WorkDoings.EXPLORING);
        }

        @Override
        public String describe() {
            return "keep with the scout";
        }
    }

    // ── continuity ───────────────────────────────────────────────────────────────────────────

    /** Everything the search is, down to the leg being walked (decision 15). */
    public record State(PartyId party, double priority, HomeSearch.State search,
                        Optional<AgentId> scout, List<Gather.Cooldown> cooldowns, long lastTick,
                        List<AgentId> companions, boolean gathered, long stepSince)
            implements ProjectState {

        public State {
            companions = List.copyOf(companions);
        }

        @Override
        public String type() {
            return "explore";
        }
    }

    public static Optional<Explore> restore(State state, long now) {
        Explore project = new Explore(state.party(), state.priority(), HomeSearch.restore(state.search()));
        project.scout = state.scout().orElse(null);
        for (Gather.Cooldown cooldown : state.cooldowns()) {
            project.cooldownUntil.put(cooldown.who(), cooldown.retryAfter());
        }
        project.lastTick = state.lastTick() >= 0 ? state.lastTick() : now;
        for (AgentId who : state.companions()) {
            project.accompanying.put(who, project.new Accompany(who));
        }
        project.gathered = state.gathered();
        project.stepSince = state.stepSince();
        project.current = project.step();
        return Optional.of(project);
    }

    /** What {@link PartyProjects} and the dispatching codec mean by {@code "explore"}. */
    public static final ProjectType TYPE = new ProjectType() {
        @Override
        public String id() {
            return "explore";
        }

        @Override
        public Optional<Explore> restore(ProjectState state, long now) {
            return state instanceof State s ? Explore.restore(s, now) : Optional.empty();
        }
    };
}
