package dev.luizloyola.autarkia.core.board;

import dev.luizloyola.anima.core.agent.AgentId;
import dev.luizloyola.anima.core.brain.BrainContext;
import dev.luizloyola.anima.core.brain.board.WorkItem;
import dev.luizloyola.anima.core.brain.history.Deed;
import dev.luizloyola.anima.core.brain.knowledge.BlockKind;
import dev.luizloyola.anima.core.brain.knowledge.BlockProbe;
import dev.luizloyola.anima.core.brain.knowledge.Region;
import dev.luizloyola.anima.core.brain.sense.Pos;
import dev.luizloyola.anima.core.brain.task.Task;
import dev.luizloyola.anima.core.inv.Inventory;
import dev.luizloyola.anima.core.inv.ItemCall;
import dev.luizloyola.anima.core.inv.Kit;
import dev.luizloyola.anima.core.log.Category;
import dev.luizloyola.autarkia.core.earthwork.CutCells;
import dev.luizloyola.autarkia.core.earthwork.FillCells;
import dev.luizloyola.autarkia.core.earthwork.FlattenPlan;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Level a cleared area to a {@link FlattenPlan}: cut what stands above each column's goal, fill
 * what lies below it (2026-10-01-flatten-design.md).
 *
 * <p>Work is offered by <b>layer and patch</b>: one item is the cells of a {@link #PATCH}-square
 * patch at one height. Cuts go from the top layer down, fills from the bottom up, and neither runs
 * more than a layer ahead of the rest of the area, so nobody digs a pit beside a tower or fills over
 * a hole.
 *
 * <p>How far each column has got is read off the world by the worker who reports, never assumed
 * from a success: a cut cell is done when it is air, a fill cell when it holds a block.
 */
public final class Flatten implements PartyProject {

    /** The side of the square patch one item covers. */
    public static final int PATCH = 4;

    /** Fruitless tries at one patch's layer before its columns are given up on. */
    static final int REFUSE_AFTER = 3;

    /** How long fills wait after a filler came back with nothing to fill with. */
    static final long MATERIAL_WAIT = 1200;

    /**
     * A column the flatten changes. {@code now} is the top of its ground as last reported; the
     * column is done when it equals {@code goal}.
     */
    public record Col(int x, int z, int ground, int goal, int now, boolean refused) {

        boolean cuts() {
            return !refused && now > goal;
        }

        boolean fills() {
            return !refused && now < goal;
        }

        Col at(int height) {
            return new Col(x, z, ground, goal, height, refused);
        }

        Col refuse() {
            return new Col(x, z, ground, goal, now, true);
        }
    }

    /** Fruitless tries at one item's key, and when it may be offered again. */
    public record Cooldown(String flavour, Pos at, int failures, long retryAfter) {
    }

    private final Region area;
    private final int tolerance;
    private final int y;
    private final FlattenPlan.Why why;
    private final double priority;
    private final Map<Long, Col> cols = new LinkedHashMap<>();
    private final Map<WorkKey, Cooldown> cooldowns = new HashMap<>();
    private long materialWaitUntil;

    private final Map<WorkKey, LayerItem> open = new LinkedHashMap<>();
    private List<WorkItem> offered = List.of();
    private final Set<WorkKey> claimed = new HashSet<>();
    private long lastTick;

    private Flatten(Region area, int tolerance, int y, FlattenPlan.Why why, double priority) {
        this.area = area;
        this.tolerance = tolerance;
        this.y = y;
        this.why = why;
        this.priority = priority;
    }

    /**
     * A flatten of {@code [minX, maxX] × [minZ, maxZ]} to a plan that was not refused.
     *
     * @throws IllegalArgumentException when the plan was refused
     */
    public static Flatten of(FlattenPlan plan, int minX, int minZ, int maxX, int maxZ, int tolerance,
                             double priority) {
        if (plan.refused()) {
            throw new IllegalArgumentException("a refused plan: " + plan.refusals());
        }
        Flatten project = new Flatten(new Region(new Pos(minX, plan.y(), minZ),
                new Pos(maxX, plan.y(), maxZ)), tolerance, plan.y(), plan.why(), priority);
        for (FlattenPlan.Column c : plan.columns()) {
            project.cols.put(key(c.x(), c.z()),
                    new Col(c.x(), c.z(), c.ground(), c.goal(), c.ground(), false));
        }
        project.refresh(0);
        return project;
    }

    private static long key(int x, int z) {
        return ((long) x << 32) | (z & 0xffffffffL);
    }

    public Region area() {
        return area;
    }

    public int y() {
        return y;
    }

    public int tolerance() {
        return tolerance;
    }

    /** Every column the flatten changes, as far as it has got. */
    public List<Col> columns() {
        return List.copyOf(cols.values());
    }

    @Override
    public double priority() {
        return priority;
    }

    @Override
    public List<WorkItem> open() {
        return offered;
    }

    @Override
    public boolean finished() {
        for (Col c : cols.values()) {
            if (c.cuts() || c.fills()) {
                return false;
            }
        }
        return true;
    }

    @Override
    public void tick(long now) {
        lastTick = now;
        refresh(now);
    }

    @Override
    public void claimed(WorkItem item) {
        keyOf(item).ifPresent(claimed::add);
    }

    @Override
    public void lapsed(WorkItem item) {
        keyOf(item).ifPresent(claimed::remove);
    }

    @Override
    public void completed(WorkItem item, BrainContext ctx) {
        if (!(item instanceof LayerItem layer)) {
            return;
        }
        claimed.remove(layer.key);
        long now = ctx.percepts().time();
        int done = settle(layer, ctx);
        if (done == 0) {
            fruitless(layer, ctx, now);
        } else {
            cooldowns.remove(layer.key);
            if (layer.cut) {
                // Spoil on the ground or in a pack: somebody has something to fill with again.
                materialWaitUntil = 0;
            }
        }
        refresh(now);
        if (finished()) {
            ctx.journal().record(Category.PROJECT, describe(), "done");
        }
    }

    @Override
    public void failed(WorkItem item, BrainContext ctx) {
        if (!(item instanceof LayerItem layer)) {
            return;
        }
        claimed.remove(layer.key);
        long now = ctx.percepts().time();
        if (settle(layer, ctx) == 0) {
            fruitless(layer, ctx, now);
        }
        refresh(now);
    }

    /**
     * A trip that moved nothing. A filler with nothing to fill with means the spoil has run out,
     * which is waiting, not failure; anything else counts against the patch's layer.
     */
    private void fruitless(LayerItem layer, BrainContext ctx, long now) {
        if (!layer.cut && !hasFill(ctx.percepts().inventory(), layer.tops.size() == layer.cells.size())) {
            materialWaitUntil = now + MATERIAL_WAIT;
            return;
        }
        Cooldown before = cooldowns.get(layer.key);
        int failures = before == null ? 1 : before.failures() + 1;
        if (failures >= REFUSE_AFTER) {
            cooldowns.remove(layer.key);
            for (Pos cell : layer.cells) {
                cols.computeIfPresent(key(cell.x(), cell.z()), (k, c) -> c.refuse());
            }
            ctx.journal().record(Category.PROJECT, describe(), "gave up on " + layer.cells.size()
                    + " columns at " + at(layer.key.at()));
            return;
        }
        cooldowns.put(layer.key, new Cooldown(layer.key.flavour(), layer.key.at(), failures,
                now + FellTrees.cooldownAfter(failures)));
    }

    private static boolean hasFill(Inventory pack, boolean onlyTops) {
        int dirt = pack.count(Stock.DIRT::matches);
        return onlyTops ? dirt > 0 : dirt > 0 || pack.count(Stock.BRIDGING::matches) > 0;
    }

    /** Reads the item's cells off the world and moves each column on; how many moved. */
    private int settle(LayerItem layer, BrainContext ctx) {
        BlockProbe probe = ctx.percepts().blocks();
        int moved = 0;
        for (Pos cell : layer.cells) {
            long k = key(cell.x(), cell.z());
            Col c = cols.get(k);
            if (c == null) {
                continue;
            }
            BlockKind there = probe.at(cell.x(), cell.y(), cell.z());
            if (layer.cut && c.now() == cell.y() && there == BlockKind.AIR) {
                cols.put(k, c.at(cell.y() - 1));
                moved++;
            } else if (!layer.cut && c.now() == cell.y() - 1 && there != BlockKind.AIR
                    && there != BlockKind.UNKNOWN && there != BlockKind.WATER) {
                cols.put(k, c.at(cell.y()));
                moved++;
            }
        }
        return moved;
    }

    /**
     * Mints what is on offer now: per patch, the top layer still to cut and the bottom layer still
     * to fill, each within a layer of the area's own. A held item is never withdrawn.
     */
    private void refresh(long now) {
        int top = Integer.MIN_VALUE;
        int bottom = Integer.MAX_VALUE;
        for (Col c : cols.values()) {
            if (c.cuts()) {
                top = Math.max(top, c.now());
            }
            if (c.fills()) {
                bottom = Math.min(bottom, c.now() + 1);
            }
        }
        Map<Long, int[]> patchLayers = new LinkedHashMap<>();
        for (Col c : cols.values()) {
            int[] layers = patchLayers.computeIfAbsent(key(patchOf(c.x()), patchOf(c.z())),
                    k -> new int[] {Integer.MIN_VALUE, Integer.MAX_VALUE});
            if (c.cuts()) {
                layers[0] = Math.max(layers[0], c.now());
            }
            if (c.fills()) {
                layers[1] = Math.min(layers[1], c.now() + 1);
            }
        }
        Map<WorkKey, LayerItem> next = new LinkedHashMap<>();
        boolean filling = materialWaitUntil <= now;
        for (Map.Entry<Long, int[]> entry : patchLayers.entrySet()) {
            int px = (int) (entry.getKey() >> 32);
            int pz = (int) (long) entry.getKey();
            int cutAt = entry.getValue()[0];
            int fillAt = entry.getValue()[1];
            if (cutAt != Integer.MIN_VALUE && cutAt >= top - 1) {
                offer(next, WorkKey.CUT, new Pos(px, cutAt, pz), now);
            }
            if (filling && fillAt != Integer.MAX_VALUE && fillAt <= bottom + 1) {
                offer(next, WorkKey.FILL, new Pos(px, fillAt, pz), now);
            }
        }
        for (WorkKey held : claimed) {
            LayerItem item = open.get(held);
            if (item != null) {
                next.putIfAbsent(held, item);
            }
        }
        open.clear();
        open.putAll(next);
        offered = List.copyOf(open.values());
    }

    private void offer(Map<WorkKey, LayerItem> next, String flavour, Pos at, long now) {
        WorkKey.AtPlace key = new WorkKey.AtPlace(flavour, at);
        Cooldown cooling = cooldowns.get(key);
        if (cooling != null && cooling.retryAfter() > now && !claimed.contains(key)) {
            return;
        }
        // The same item while its cells stand: the arbiter claims what it was offered a tick ago,
        // and a claim on an item no longer on offer finds no project to tell.
        LayerItem fresh = layer(key);
        LayerItem existing = open.get(key);
        next.put(key, existing != null && existing.cells.equals(fresh.cells) ? existing : fresh);
    }

    /** The cells of one patch at one layer, read from the columns as they stand. */
    private LayerItem layer(WorkKey.AtPlace key) {
        boolean cut = WorkKey.CUT.equals(key.flavour());
        Pos at = key.at();
        List<Pos> cells = new ArrayList<>();
        List<Pos> tops = new ArrayList<>();
        for (int x = at.x(); x < at.x() + PATCH; x++) {
            for (int z = at.z(); z < at.z() + PATCH; z++) {
                Col c = cols.get(key(x, z));
                if (c == null) {
                    continue;
                }
                if (cut && c.cuts() && c.now() == at.y()) {
                    cells.add(new Pos(x, at.y(), z));
                } else if (!cut && c.fills() && c.now() + 1 == at.y()) {
                    Pos cell = new Pos(x, at.y(), z);
                    cells.add(cell);
                    if (c.goal() == at.y()) {
                        tops.add(cell);
                    }
                }
            }
        }
        return new LayerItem(key, cut, cells, tops);
    }

    private static int patchOf(int coordinate) {
        return Math.floorDiv(coordinate, PATCH) * PATCH;
    }

    // ── durable names ────────────────────────────────────────────────────────────────────────

    @Override
    public Optional<WorkKey> keyOf(WorkItem item) {
        return item instanceof LayerItem layer && open.get(layer.key) == layer
                ? Optional.of(layer.key) : Optional.empty();
    }

    @Override
    public Optional<WorkItem> itemFor(WorkKey key) {
        return Optional.ofNullable(open.get(key));
    }

    // ── what is kept ─────────────────────────────────────────────────────────────────────────

    /** The spoil still wanted for filling stays in the pack rather than going to a chest. */
    @Override
    public List<ItemCall> reserved() {
        int wanted = 0;
        for (Col c : cols.values()) {
            if (c.fills()) {
                wanted += c.goal() - c.now();
            }
        }
        return wanted == 0 ? List.of()
                : List.of(ItemCall.want(Stock.BRIDGING, Math.min(wanted, 64)));
    }

    // ── the readout ──────────────────────────────────────────────────────────────────────────

    @Override
    public String describe() {
        int cutLeft = 0;
        int cutAll = 0;
        int fillLeft = 0;
        int fillAll = 0;
        int refused = 0;
        for (Col c : cols.values()) {
            cutAll += Math.max(0, c.ground() - c.goal());
            fillAll += Math.max(0, c.goal() - c.ground());
            if (c.refused()) {
                refused++;
            } else {
                cutLeft += Math.max(0, c.now() - c.goal());
                fillLeft += Math.max(0, c.goal() - c.now());
            }
        }
        StringBuilder line = new StringBuilder("flatten " + at(area.min()) + " to " + at(area.max())
                + " at y " + y + " ±" + tolerance + " (" + why.name().toLowerCase() + ") — cut "
                + (cutAll - cutLeft) + "/" + cutAll + ", fill " + (fillAll - fillLeft) + "/" + fillAll);
        if (materialWaitUntil > lastTick) {
            line.append(" · waiting on dirt");
        }
        if (refused > 0) {
            line.append(" · ").append(refused).append(" columns given up on");
        }
        return line.toString();
    }

    private static String at(Pos pos) {
        return "(" + pos.x() + ", " + pos.z() + ")";
    }

    // ── the item ─────────────────────────────────────────────────────────────────────────────

    /** One patch's cells at one layer, cut or filled. */
    private final class LayerItem implements WorkItem {

        private final WorkKey.AtPlace key;
        private final boolean cut;
        private final List<Pos> cells;
        private final List<Pos> tops;

        private LayerItem(WorkKey.AtPlace key, boolean cut, List<Pos> cells, List<Pos> tops) {
            this.key = key;
            this.cut = cut;
            this.cells = List.copyOf(cells);
            this.tops = List.copyOf(tops);
        }

        @Override
        public double priority() {
            return priority;
        }

        @Override
        public double estimatedCost(BrainContext ctx) {
            Pos here = ctx.percepts().position();
            double dx = key.at().x() + PATCH / 2.0 - here.x();
            double dz = key.at().z() + PATCH / 2.0 - here.z();
            return FellTrees.COST_AT_RANGE * Math.min(1.0, Math.sqrt(dx * dx + dz * dz) / FellTrees.COST_RANGE);
        }

        @Override
        public Task root() {
            return cut ? new CutCells(cells) : new FillCells(cells, tops);
        }

        /** Tools for a cut, wanted not needed; spoil for a fill, from the pack or the store. */
        @Override
        public Kit kit() {
            if (cut) {
                return Kit.of(ItemCall.want(Stock.SHOVELS, 1), ItemCall.want(Stock.PICKAXES, 1));
            }
            return tops.isEmpty() ? Kit.of(ItemCall.want(Stock.BRIDGING, cells.size()))
                    : Kit.of(ItemCall.want(Stock.DIRT, tops.size()),
                            ItemCall.want(Stock.BRIDGING, cells.size()));
        }

        @Override
        public boolean buildsOnTheWay() {
            return true;
        }

        @Override
        public Deed doing() {
            return Deed.of(WorkDoings.LEVELLING);
        }

        @Override
        public String describe() {
            return (cut ? "cut " : "fill ") + cells.size() + " at " + at(key.at()) + " y " + key.at().y();
        }

        @Override
        public String progress(BrainContext ctx) {
            return describe();
        }
    }

    // ── continuity ───────────────────────────────────────────────────────────────────────────

    /** Everything a flatten carries between ticks. The plan is not re-made on load. */
    public record State(Region area, int tolerance, int y, String why, double priority, List<Col> cols,
                        List<Cooldown> cooldowns, long materialWaitUntil) implements ProjectState {

        @Override
        public String type() {
            return "flatten";
        }
    }

    @Override
    public State snapshot() {
        return new State(area, tolerance, y, why.name(), priority, List.copyOf(cols.values()),
                List.copyOf(cooldowns.values()), materialWaitUntil);
    }

    public static Flatten restore(State state, long now) {
        FlattenPlan.Why why;
        try {
            why = FlattenPlan.Why.valueOf(state.why());
        } catch (IllegalArgumentException unknown) {
            why = FlattenPlan.Why.GIVEN;
        }
        Flatten project = new Flatten(state.area(), state.tolerance(), state.y(), why, state.priority());
        for (Col c : state.cols()) {
            project.cols.put(key(c.x(), c.z()), c);
        }
        for (Cooldown c : state.cooldowns()) {
            project.cooldowns.put(new WorkKey.AtPlace(c.flavour(), c.at()), c);
        }
        project.materialWaitUntil = state.materialWaitUntil();
        project.lastTick = now;
        project.refresh(now);
        return project;
    }

    /** What {@link PartyProjects} and the dispatching codec mean by {@code "flatten"}. */
    public static final ProjectType TYPE = new ProjectType() {
        @Override
        public String id() {
            return "flatten";
        }

        @Override
        public Optional<Flatten> restore(ProjectState state, long now) {
            return state instanceof State s ? Optional.of(Flatten.restore(s, now)) : Optional.empty();
        }
    };
}
