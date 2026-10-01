package dev.luizloyola.autarkia.core.board;

import dev.luizloyola.anima.core.agent.AgentId;
import dev.luizloyola.anima.core.brain.BrainContext;
import dev.luizloyola.anima.core.brain.board.WorkItem;
import dev.luizloyola.anima.core.brain.history.Deed;
import dev.luizloyola.anima.core.brain.knowledge.BlockProbe;
import dev.luizloyola.anima.core.brain.sense.Pos;
import dev.luizloyola.anima.core.brain.task.Task;
import dev.luizloyola.anima.core.inv.Inventory;
import dev.luizloyola.anima.core.inv.ItemCall;
import dev.luizloyola.anima.core.inv.ItemSpec;
import dev.luizloyola.anima.core.inv.Kit;
import dev.luizloyola.anima.core.log.Category;
import dev.luizloyola.autarkia.core.builder.LayPiece;
import dev.luizloyola.autarkia.core.builder.Laying;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * Put a building up in its proved order (builder spec, *Work items*, rung 2). The order is cut into
 * pieces — a run of steps in one section — and one piece is offered at a time, so the proof's
 * order holds; two builders on one house is the next rung. A piece's kit is the blocks it places,
 * from the store or made.
 *
 * <p>What stands is read off the world by the builder who reports, never assumed from a success: a
 * step is done when its cell holds its block.
 */
public final class Build implements PartyProject {

    /** The most steps one trip carries. */
    static final int PIECE = 24;

    /** Fruitless tries at one step before it is handed back. */
    static final int REFUSE_AFTER = 3;

    /** How long a piece waits after its builder came back without what it needed. */
    static final long MATERIAL_WAIT = 1200;

    private final UUID structure;
    private final String name;
    private final List<Laying> order;
    private final double priority;
    private final boolean[] done;
    private final int[] failures;
    private final Set<Integer> refused = new TreeSet<>();
    private final Set<AgentId> builders = new LinkedHashSet<>();
    private long retryAfter;
    private long materialWaitUntil;
    private String shortOf = "";
    private long lastTick;

    private PieceItem open;

    public Build(UUID structure, String name, List<Laying> order, double priority) {
        this.structure = Objects.requireNonNull(structure, "structure");
        this.name = name;
        this.order = List.copyOf(order);
        this.priority = priority;
        this.done = new boolean[this.order.size()];
        this.failures = new int[this.order.size()];
    }

    public UUID structure() {
        return structure;
    }

    /** The blueprint it builds. */
    public String name() {
        return name;
    }

    public List<Laying> order() {
        return order;
    }

    /** How many steps stand. */
    public int placed() {
        int count = 0;
        for (boolean d : done) {
            if (d) {
                count++;
            }
        }
        return count;
    }

    /** The steps handed back, by index in {@link #order}. */
    public Set<Integer> refused() {
        return Set.copyOf(refused);
    }

    public Set<AgentId> builders() {
        return Set.copyOf(builders);
    }

    /** Marks the steps already standing — a build posted again over its own start. */
    public void standing(Predicate<Laying> stands) {
        for (int i = 0; i < order.size(); i++) {
            if (!done[i] && stands.test(order.get(i))) {
                done[i] = true;
            }
        }
    }

    private static boolean stands(BlockProbe probe, Laying step) {
        Pos cell = step.cell();
        return step.standsAs(probe.idAt(cell.x(), cell.y(), cell.z()), probe.stateAt(cell.x(), cell.y(), cell.z()));
    }

    @Override
    public double priority() {
        return priority;
    }

    @Override
    public List<WorkItem> open() {
        if (open == null || retryAfter > lastTick || materialWaitUntil > lastTick) {
            return List.of();
        }
        return List.of(open);
    }

    @Override
    public boolean finished() {
        return next() < 0;
    }

    private int next() {
        for (int i = 0; i < order.size(); i++) {
            if (!done[i] && !refused.contains(i)) {
                return i;
            }
        }
        return -1;
    }

    @Override
    public void tick(long now) {
        lastTick = now;
        refresh();
    }

    /** The next piece: from the first step still to place, a run in its section. */
    private void refresh() {
        int first = next();
        if (first < 0) {
            open = null;
            return;
        }
        List<Integer> steps = new ArrayList<>();
        for (int i = first; i < order.size() && steps.size() < PIECE; i++) {
            if (order.get(i).section() != order.get(first).section()) {
                break;
            }
            if (!done[i] && !refused.contains(i)) {
                steps.add(i);
            }
        }
        if (open == null || !open.steps.equals(steps)) {
            open = new PieceItem(new WorkKey.AtPlace(WorkKey.BUILD, order.get(first).cell()), steps);
        }
    }

    @Override
    public void claimed(WorkItem item, AgentId who) {
        if (item == open) {
            builders.add(who);
        }
    }

    @Override
    public void completed(WorkItem item, BrainContext ctx) {
        report(item, ctx);
    }

    @Override
    public void failed(WorkItem item, BrainContext ctx) {
        report(item, ctx);
    }

    private void report(WorkItem item, BrainContext ctx) {
        if (!(item instanceof PieceItem piece)) {
            return;
        }
        long now = ctx.percepts().time();
        BlockProbe probe = ctx.percepts().blocks();
        int moved = 0;
        for (int i : piece.steps) {
            if (!done[i] && stands(probe, order.get(i))) {
                done[i] = true;
                moved++;
            }
        }
        if (moved > 0) {
            shortOf = "";
            retryAfter = 0;
        } else {
            fruitless(piece, ctx, now);
        }
        refresh();
        if (finished()) {
            ctx.journal().record(Category.PROJECT, describe(), "done");
        }
    }

    /**
     * A trip that placed nothing. Short of the blocks is waiting, said in the builder's journal
     * (ruling 24); otherwise it counts against the first step, which is handed back at the last.
     */
    private void fruitless(PieceItem piece, BrainContext ctx, long now) {
        Map<String, Integer> missing = piece.missing(ctx.percepts().inventory());
        if (!missing.isEmpty()) {
            shortOf = words(missing);
            materialWaitUntil = now + MATERIAL_WAIT;
            ctx.journal().record(Category.PROJECT, describe(), "short of " + shortOf);
            return;
        }
        int first = piece.steps.get(0);
        failures[first]++;
        if (failures[first] >= REFUSE_AFTER) {
            refused.add(first);
            ctx.journal().record(Category.PROJECT, describe(), "handed back " + order.get(first).placing()
                    + ": it would not go in");
            return;
        }
        retryAfter = now + FellTrees.cooldownAfter(failures[first]);
    }

    private static String words(Map<String, Integer> counts) {
        List<String> words = new ArrayList<>();
        counts.forEach((id, count) -> words.add(count + " " + id));
        return String.join(", ", words);
    }

    // ── durable names ────────────────────────────────────────────────────────────────────────

    @Override
    public Optional<WorkKey> keyOf(WorkItem item) {
        return item instanceof PieceItem piece && piece == open ? Optional.of(piece.key) : Optional.empty();
    }

    @Override
    public Optional<WorkItem> itemFor(WorkKey key) {
        return open != null && open.key.equals(key) ? Optional.of(open) : Optional.empty();
    }

    // ── the readout ──────────────────────────────────────────────────────────────────────────

    @Override
    public String describe() {
        StringBuilder line = new StringBuilder("build " + name + " — " + placed() + "/" + order.size() + " placed");
        if (!refused.isEmpty()) {
            line.append(", ").append(refused.size()).append(" handed back");
        }
        if (materialWaitUntil > lastTick && !shortOf.isEmpty()) {
            line.append(" · short of ").append(shortOf);
        }
        return line.toString();
    }

    // ── the item ─────────────────────────────────────────────────────────────────────────────

    /** A run of steps for one builder, and the blocks they take. */
    private final class PieceItem implements WorkItem {

        private final WorkKey.AtPlace key;
        private final List<Integer> steps;

        private PieceItem(WorkKey.AtPlace key, List<Integer> steps) {
            this.key = key;
            this.steps = List.copyOf(steps);
        }

        /** Items by id, in the order the piece first places them. */
        private Map<String, Integer> bill() {
            Map<String, Integer> bill = new LinkedHashMap<>();
            for (int i : steps) {
                Laying step = order.get(i);
                bill.merge(step.placing().itemId(), step.count(), Integer::sum);
            }
            return bill;
        }

        /** What the piece's clicks hold: a plant or a bucket for each step, a tool once for all. */
        private Map<Set<String>, Integer> uses() {
            Map<Set<String>, Integer> uses = new LinkedHashMap<>();
            for (int i : steps) {
                for (Laying.Use use : order.get(i).uses()) {
                    uses.merge(use.items(), 1, use.consumed() ? Integer::sum : Math::max);
                }
            }
            return uses;
        }

        Map<String, Integer> missing(Inventory pack) {
            Map<String, Integer> missing = new LinkedHashMap<>();
            bill().forEach((id, count) -> {
                int held = pack.count(id);
                if (held < count) {
                    missing.put(id, count - held);
                }
            });
            return missing;
        }

        @Override
        public double priority() {
            return priority;
        }

        @Override
        public double estimatedCost(BrainContext ctx) {
            Pos here = ctx.percepts().position();
            Pos at = key.at();
            double dx = at.x() - here.x();
            double dz = at.z() - here.z();
            return FellTrees.COST_AT_RANGE * Math.min(1.0, Math.sqrt(dx * dx + dz * dz) / FellTrees.COST_RANGE);
        }

        @Override
        public Task root() {
            return new LayPiece(steps.stream().map(order::get).toList());
        }

        @Override
        public Kit kit() {
            List<ItemCall> calls = new ArrayList<>();
            bill().forEach((id, count) -> calls.add(ItemCall.need(ItemSpec.anyOf(Set.of(id)), count)));
            uses().forEach((items, count) -> calls.add(ItemCall.need(ItemSpec.anyOf(items), count)));
            return Kit.of(calls.toArray(ItemCall[]::new));
        }

        @Override
        public Deed doing() {
            return Deed.of(WorkDoings.BUILDING);
        }

        @Override
        public String describe() {
            Laying first = order.get(steps.get(0));
            return "build " + steps.size() + " of " + name + "'s "
                    + first.section().name().toLowerCase(java.util.Locale.ROOT) + " from (" + first.cell().x() + ", "
                    + first.cell().y() + ", " + first.cell().z() + ")";
        }

        @Override
        public String progress(BrainContext ctx) {
            return describe();
        }
    }

    // ── continuity ───────────────────────────────────────────────────────────────────────────

    /** Everything a build carries between ticks: the world steps are the plan's copy. */
    public record State(UUID structure, String name, double priority, List<Laying> order, List<Integer> done,
                        List<Integer> failures, List<Integer> refused, List<AgentId> builders, long retryAfter,
                        long materialWaitUntil, String shortOf) implements ProjectState {

        @Override
        public String type() {
            return "build";
        }
    }

    @Override
    public State snapshot() {
        List<Integer> placed = new ArrayList<>();
        List<Integer> tries = new ArrayList<>();
        for (int i = 0; i < order.size(); i++) {
            if (done[i]) {
                placed.add(i);
            }
            tries.add(failures[i]);
        }
        return new State(structure, name, priority, order, placed, tries, List.copyOf(refused),
                List.copyOf(builders), retryAfter, materialWaitUntil, shortOf);
    }

    public static Build restore(State state, long now) {
        Build project = new Build(state.structure(), state.name(), state.order(), state.priority());
        for (int i : state.done()) {
            if (i >= 0 && i < project.done.length) {
                project.done[i] = true;
            }
        }
        for (int i = 0; i < state.failures().size() && i < project.failures.length; i++) {
            project.failures[i] = state.failures().get(i);
        }
        project.refused.addAll(state.refused());
        project.builders.addAll(state.builders());
        project.retryAfter = state.retryAfter();
        project.materialWaitUntil = state.materialWaitUntil();
        project.shortOf = state.shortOf();
        project.lastTick = now;
        project.refresh();
        return project;
    }

    /** What {@link PartyProjects} and the dispatching codec mean by {@code "build"}. */
    public static final ProjectType TYPE = new ProjectType() {
        @Override
        public String id() {
            return "build";
        }

        @Override
        public Optional<Build> restore(ProjectState state, long now) {
            return state instanceof State s ? Optional.of(Build.restore(s, now)) : Optional.empty();
        }
    };
}
