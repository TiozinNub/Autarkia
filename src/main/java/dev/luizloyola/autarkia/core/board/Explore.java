package dev.luizloyola.autarkia.core.board;

import dev.luizloyola.anima.core.agent.AgentId;
import dev.luizloyola.anima.core.brain.BrainContext;
import dev.luizloyola.anima.core.brain.board.WorkItem;
import dev.luizloyola.anima.core.brain.history.Deed;
import dev.luizloyola.anima.core.brain.sense.Pos;
import dev.luizloyola.anima.core.brain.task.GoTo;
import dev.luizloyola.anima.core.brain.task.LookRound;
import dev.luizloyola.anima.core.brain.task.Task;
import dev.luizloyola.anima.core.log.Category;
import dev.luizloyola.anima.core.social.PartyId;
import dev.luizloyola.anima.core.store.Store;
import dev.luizloyola.autarkia.core.direction.HomeJudge;
import dev.luizloyola.autarkia.core.direction.HomeJudge.Candidate;
import dev.luizloyola.autarkia.core.direction.HomeLooking;
import dev.luizloyola.autarkia.core.direction.HomeSearch;
import dev.luizloyola.autarkia.core.direction.HomeSearch.Phase;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
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

    private static volatile @Nullable HomeLooking looking;

    private final PartyId party;
    private final double priority;
    private final HomeSearch search;
    private @Nullable AgentId scout;
    private final Map<AgentId, Long> cooldownUntil = new LinkedHashMap<>();
    private long lastTick;

    /** The one item on offer, rebuilt as the search moves. The board leases by identity. */
    private Step current;

    public Explore(PartyId party, double priority) {
        this(party, priority, new HomeSearch());
    }

    private Explore(PartyId party, double priority, HomeSearch search) {
        this.party = Objects.requireNonNull(party, "party");
        this.priority = priority;
        this.search = search;
        this.current = new Step(search.phase());
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

    @Override
    public List<WorkItem> open() {
        return searching() ? List.of(current) : List.of();
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

    @Override
    public boolean offerableTo(WorkItem item, AgentId asker, BrainContext ctx) {
        Long until = cooldownUntil.get(asker);
        return until == null || until <= lastTick;
    }

    @Override
    public void claimed(WorkItem item, AgentId who) {
        if (item == current) {
            scout = who;
        }
    }

    @Override
    public void completed(WorkItem item, BrainContext ctx) {
        if (item != current) {
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
        current = new Step(search.phase());
    }

    @Override
    public void failed(WorkItem item, AgentId who, BrainContext ctx) {
        if (item != current) {
            return;
        }
        if (search.phase() == Phase.WALK) {
            journal(ctx, search.failedLeg(ctx.percepts().position()));
            current = new Step(search.phase());
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
                List.copyOf(cooldowns), lastTick);
    }

    /** The scout's own step, named by the scout: nobody else is handed it back after a restart. */
    @Override
    public Optional<WorkKey> keyOf(WorkItem item) {
        return item == current && scout != null && searching()
                ? Optional.of(new WorkKey.ForMember(WorkKey.EXPLORE, scout))
                : Optional.empty();
    }

    @Override
    public Optional<WorkItem> itemFor(WorkKey key) {
        return searching() && scout != null && key.equals(new WorkKey.ForMember(WorkKey.EXPLORE, scout))
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

    /** One step of the search: look round, walk the leg, or go and settle. */
    private final class Step implements WorkItem {
        private final Phase phase;

        private Step(Phase phase) {
            this.phase = phase;
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
            return switch (phase) {
                case LOOK -> "look round for a place to live";
                case WALK -> "walk on to " + at(search.legEnd());
                case SETTLE -> "go and settle at " + at(search.yard());
                default -> "explore";
            };
        }
    }

    // ── continuity ───────────────────────────────────────────────────────────────────────────

    /** Everything the search is, down to the leg being walked (decision 15). */
    public record State(PartyId party, double priority, HomeSearch.State search,
                        Optional<AgentId> scout, List<Gather.Cooldown> cooldowns, long lastTick)
            implements ProjectState {

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
