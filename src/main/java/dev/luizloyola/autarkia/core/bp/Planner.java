package dev.luizloyola.autarkia.core.bp;

import dev.luizloyola.autarkia.core.bp.Binder.Fixture;
import dev.luizloyola.autarkia.core.bp.Blueprint.EntryInfo;
import dev.luizloyola.autarkia.core.bp.Blueprint.Facing;
import dev.luizloyola.autarkia.core.bp.Blueprint.Outcome;
import dev.luizloyola.autarkia.core.bp.Blueprint.SlotInfo;
import dev.luizloyola.autarkia.core.bp.Blueprint.SlotKind;
import dev.luizloyola.autarkia.core.bp.BpSource.Named;
import dev.luizloyola.autarkia.core.bp.BpSource.SlotRef;
import dev.luizloyola.autarkia.core.bp.BpSource.Term;
import dev.luizloyola.autarkia.core.bp.BuildPlan.BillLine;
import dev.luizloyola.autarkia.core.bp.BuildPlan.CellKind;
import dev.luizloyola.autarkia.core.bp.Chooser.Choice;
import dev.luizloyola.autarkia.core.bp.Diagnostic.Cell;
import dev.luizloyola.autarkia.core.bp.Dictionary.BlockInfo;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.random.RandomGenerator;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;

/**
 * Stage 3: a {@link Blueprint}, pins and a {@link Chooser} to a {@link BuildPlan}. Every option
 * settles, every mix rolls and every torch finds its wall here, so nothing past this point sees a
 * slot or a union — only blocks (reader spec, Stage 3).
 */
public final class Planner {

    /** Properties that count items into one block: two slabs make a double, four candles share one. */
    private static final List<String> COUNTS = List.of("candles", "pickles", "eggs", "flower_amount",
            "segment_amount", "layers");

    /**
     * One way a union can come out: a material or a block settled now, or a slot read again for
     * each cell. Weights make a draw uniform over the union's terms, not over what hides in them.
     */
    private record Way(double weight, @Nullable String material, @Nullable Outcome block, @Nullable SlotRef ref) {
    }

    /** A slot, or a legend entry's inline union — the entry's own roll. */
    private static final class Union {
        final String name;
        final @Nullable SlotInfo slot;
        final @Nullable Binding binding;
        final List<Way> ways;
        @Nullable Way fixed;

        Union(String name, @Nullable SlotInfo slot, @Nullable Binding binding, List<Way> ways) {
            this.name = name;
            this.slot = slot;
            this.binding = binding;
            this.ways = ways;
        }

        boolean materials() {
            return slot != null && slot.kind() == SlotKind.MATERIAL;
        }
    }

    private final Blueprint bp;
    private final Dictionary dict;
    private final Support support;
    private final RandomGenerator random;
    private final Diagnostics out;
    private final Map<Integer, Union> slots = new LinkedHashMap<>();
    private final Map<Character, Union> entries = new LinkedHashMap<>();

    private Planner(Blueprint bp, Dictionary dict, Support support, RandomGenerator random, Diagnostics out) {
        this.bp = bp;
        this.dict = dict;
        this.support = support;
        this.random = random;
        this.out = out;
    }

    /**
     * The plan, or null when a pin or an attachment was wrong — every problem reported either way.
     *
     * @param support what holds a torch or a lantern up
     * @param pins    slot number to a member of its domain, a material or a block as the slot takes
     * @param chooser settles every option not pinned
     * @param random  rolls every mix, per cell
     */
    public static @Nullable BuildPlan plan(Blueprint bp, Dictionary dict, Support support, Map<Integer, String> pins,
                                           Chooser chooser, RandomGenerator random, Diagnostics out) {
        return new Planner(bp, dict, support, random, out).plan(pins, chooser);
    }

    private @Nullable BuildPlan plan(Map<Integer, String> pins, Chooser chooser) {
        for (SlotInfo slot : bp.slots()) {
            slots.put(slot.number(), new Union("slot " + slot.number(), slot, slot.binding(),
                    ways(slot.terms(), slot)));
        }
        for (EntryInfo entry : bp.legend().values()) {
            entries.put(entry.glyph(), new Union("'" + entry.glyph() + "'", null, entry.binding(),
                    ways(entry.terms(), null)));
        }
        pin(pins);
        if (out.hasErrors()) {
            return null;
        }
        // In file order, so a slot's choices read what the slots above it already settled.
        for (Union union : slots.values()) {
            settle(union, chooser);
        }
        for (Union union : entries.values()) {
            settle(union, chooser);
        }

        int cells = bp.layers() * bp.depth() * bp.width();
        CellKind[] kinds = new CellKind[cells];
        Outcome[] states = new Outcome[cells];
        boolean[] partner = new boolean[cells];
        for (int layer = bp.minLayer(); layer <= bp.maxLayer(); layer++) {
            for (int z = 0; z < bp.depth(); z++) {
                for (int x = 0; x < bp.width(); x++) {
                    int i = index(layer, x, z);
                    EntryInfo entry = bp.entryAt(layer, x, z);
                    if (entry == null) {
                        char glyph = bp.glyph(layer, x, z);
                        kinds[i] = glyph == Blueprint.ANY ? CellKind.KEEP
                                : glyph == Blueprint.TERRAIN ? CellKind.TERRAIN : CellKind.AIR;
                    } else {
                        kinds[i] = CellKind.BLOCK;
                        states[i] = block(entries.get(entry.glyph()), random);
                    }
                }
            }
        }
        fixtures(kinds, states, partner);
        attach(kinds, states);
        if (out.hasErrors()) {
            return null;
        }
        Map<Integer, String> bindings = new LinkedHashMap<>();
        for (Union union : slots.values()) {
            if (union.fixed != null && union.slot != null) {
                bindings.put(union.slot.number(), label(union, union.fixed));
            }
        }
        Map<String, Integer> itemless = new TreeMap<>();
        List<BillLine> bill = bill(kinds, states, partner, itemless);
        return new BuildPlan(bp.id(), bp.headers().version(), bp.width(), bp.depth(), bp.minLayer(), bp.layers(),
                kinds, states, bindings, bill, itemless);
    }

    private int index(int layer, int x, int z) {
        return ((layer - bp.minLayer()) * bp.depth() + z) * bp.width() + x;
    }

    // ── unions ──────────────────────────────────────────────────────────────────────────────

    /**
     * Uniform over the terms, then over what a term stands for: {@code oak|nether_wood} is oak half
     * the time and crimson a quarter. A term narrowing left with nothing drops out of the draw.
     */
    private List<Way> ways(List<Term> terms, @Nullable SlotInfo slot) {
        boolean materials = slot != null && slot.kind() == SlotKind.MATERIAL;
        List<List<Way>> perTerm = new ArrayList<>();
        for (Term term : terms) {
            List<Way> ways = new ArrayList<>();
            if (term instanceof Named named && materials) {
                for (String leaf : dict.leaves(Ids.qualify(named.name()))) {
                    if (slot.domain().contains(leaf)) {
                        ways.add(new Way(0, leaf, null, null));
                    }
                }
            } else if (term instanceof Named named) {
                ways.add(new Way(0, null, new Outcome(Ids.qualify(named.name()), named.props()), null));
            } else {
                ways.add(new Way(0, null, null, (SlotRef) term));
            }
            if (!ways.isEmpty()) {
                perTerm.add(ways);
            }
        }
        // Keyed by what a way yields, so oak|overworld_wood offers oak once, with both terms' shares.
        Map<List<Object>, Way> merged = new LinkedHashMap<>();
        for (List<Way> ways : perTerm) {
            for (Way way : ways) {
                double weight = 1.0 / perTerm.size() / ways.size();
                SlotRef ref = way.ref();
                List<Object> key = Arrays.asList(way.material(), way.block(), ref == null ? null : ref.slot(),
                        ref == null ? null : ref.form(), ref == null ? null : ref.props());
                merged.merge(key, new Way(weight, way.material(), way.block(), ref),
                        (was, next) -> new Way(was.weight() + next.weight(), was.material(), was.block(), was.ref()));
            }
        }
        return new ArrayList<>(merged.values());
    }

    /** A pin collapses any slot, a mix included (decision: Luiz), to a member of its domain. */
    private void pin(Map<Integer, String> pins) {
        for (Map.Entry<Integer, String> pin : pins.entrySet()) {
            Union union = slots.get(pin.getKey());
            if (union == null || union.slot == null) {
                out.error("pin_slot", 0, 0, "no slot " + pin.getKey() + " to pin" + (slots.isEmpty() ? ""
                        : "; this blueprint has " + slots.keySet().stream().map(String::valueOf)
                        .collect(Collectors.joining(", "))));
                continue;
            }
            SlotInfo slot = union.slot;
            String value = Ids.qualify(pin.getValue());
            Set<String> brief = slot.domain().stream().map(Ids::brief).collect(Collectors.toCollection(
                    TreeSet::new));
            if (!slot.domain().contains(value)) {
                out.error("pin_domain", 0, 0, "slot " + slot.number() + " takes one of " + String.join(", ", brief)
                        + " — not '" + pin.getValue() + "'" + Suggest.hint(Ids.brief(value), brief));
                continue;
            }
            if (union.materials()) {
                union.fixed = new Way(1, value, null, null);
            } else {
                for (Outcome outcome : slot.outcomes()) {
                    if (outcome.block().equals(value)) {
                        union.fixed = new Way(1, null, outcome, null);
                        break;
                    }
                }
            }
        }
    }

    private void settle(Union union, Chooser chooser) {
        if (union.fixed != null || union.binding != Binding.OPTION || union.ways.isEmpty()) {
            return;
        }
        List<Choice> choices = new ArrayList<>();
        for (Way way : union.ways) {
            Set<String> blocks = new LinkedHashSet<>();
            if (union.materials()) {
                for (String leaf : materials(way)) {
                    for (String form : union.slot.forms()) {
                        dict.lookup(leaf, form).ifPresent(blocks::add);
                    }
                }
            } else {
                blocks(way).forEach(outcome -> blocks.add(outcome.block()));
            }
            choices.add(new Choice(label(union, way), blocks, way.weight()));
        }
        int picked = chooser.choose(union.name, choices);
        union.fixed = union.ways.get(Math.max(0, Math.min(picked, union.ways.size() - 1)));
    }

    /** A settled way as a pin would write it; {@code $n} when it reads a slot still mixing. */
    private String label(Union union, Way way) {
        if (union.materials()) {
            Set<String> reach = materials(way);
            return reach.size() == 1 ? Ids.brief(reach.iterator().next()) : "$" + way.ref().slot();
        }
        Set<Outcome> reach = blocks(way);
        return reach.size() == 1 ? Ids.brief(reach.iterator().next().block()) : "$" + way.ref().slot();
    }

    private static Way draw(List<Way> ways, RandomGenerator random) {
        double roll = random.nextDouble();
        for (Way way : ways) {
            roll -= way.weight();
            if (roll < 0) {
                return way;
            }
        }
        return ways.get(ways.size() - 1);
    }

    // ── one cell's roll ─────────────────────────────────────────────────────────────────────

    private String material(Union union, RandomGenerator random) {
        return material(union.fixed != null ? union.fixed : draw(union.ways, random), random);
    }

    private String material(Way way, RandomGenerator random) {
        return way.material() != null ? way.material() : material(slots.get(way.ref().slot()), random);
    }

    private Outcome block(Union union, RandomGenerator random) {
        return block(union.fixed != null ? union.fixed : draw(union.ways, random), random);
    }

    private Outcome block(Way way, RandomGenerator random) {
        if (way.block() != null) {
            return way.block();
        }
        SlotRef ref = way.ref();
        Union target = slots.get(ref.slot());
        if (target.materials()) {
            String leaf = material(target, random);
            return new Outcome(dict.lookup(leaf, ref.form()).orElseThrow(), ref.props());
        }
        return overlay(block(target, random), ref.props());
    }

    private static Outcome overlay(Outcome outcome, Map<String, String> props) {
        if (props.isEmpty()) {
            return outcome;
        }
        Map<String, String> merged = new TreeMap<>(outcome.props());
        merged.putAll(props);
        return new Outcome(outcome.block(), merged);
    }

    // ── what a union can still come out as ──────────────────────────────────────────────────

    private Set<String> materials(Union union) {
        if (union.fixed != null) {
            return materials(union.fixed);
        }
        Set<String> reach = new LinkedHashSet<>();
        union.ways.forEach(way -> reach.addAll(materials(way)));
        return reach;
    }

    private Set<String> materials(Way way) {
        return way.material() != null ? Set.of(way.material()) : materials(slots.get(way.ref().slot()));
    }

    private Set<Outcome> blocks(Union union) {
        if (union.fixed != null) {
            return blocks(union.fixed);
        }
        Set<Outcome> reach = new LinkedHashSet<>();
        union.ways.forEach(way -> reach.addAll(blocks(way)));
        return reach;
    }

    private Set<Outcome> blocks(Way way) {
        if (way.block() != null) {
            return Set.of(way.block());
        }
        SlotRef ref = way.ref();
        Union target = slots.get(ref.slot());
        Set<Outcome> reach = new LinkedHashSet<>();
        if (target.materials()) {
            for (String leaf : materials(target)) {
                dict.lookup(leaf, ref.form()).ifPresent(block -> reach.add(new Outcome(block, ref.props())));
            }
        } else {
            blocks(target).forEach(outcome -> reach.add(overlay(outcome, ref.props())));
        }
        return reach;
    }

    // ── fixtures ────────────────────────────────────────────────────────────────────────────

    /**
     * A fixture's parts share one binding even under a mix (format spec), so the other part is
     * copied from the main one rather than rolled: a drawn partner is overwritten, an inferred one
     * written. The main part says its part outright — a bed left unsaid is its head, though
     * vanilla's default bed is the foot.
     */
    private void fixtures(CellKind[] kinds, Outcome[] states, boolean[] partner) {
        for (int layer = bp.minLayer(); layer <= bp.maxLayer(); layer++) {
            for (int z = 0; z < bp.depth(); z++) {
                for (int x = 0; x < bp.width(); x++) {
                    int i = index(layer, x, z);
                    Outcome state = states[i];
                    BlockInfo info = state == null ? null : dict.block(state.block()).orElse(null);
                    Fixture fixture = info == null ? null : Binder.fixtureOf(info);
                    if (fixture == null || partner[i]
                            || !fixture.main.equals(state.props().getOrDefault(fixture.key, fixture.main))) {
                        continue;
                    }
                    Map<String, String> props = new TreeMap<>(state.props());
                    props.put(fixture.key, fixture.main);
                    states[i] = new Outcome(state.block(), props);
                    Facing facing = Facing.of(props.getOrDefault("facing", info.defaults().getOrDefault("facing",
                            "north")));
                    Cell other = Binder.partnerOf(fixture, facing, new Cell(layer, x, z));
                    int j = index(other.layer(), other.x(), other.z());
                    props.put(fixture.key, fixture.other);
                    kinds[j] = CellKind.BLOCK;
                    states[j] = new Outcome(state.block(), props);
                    partner[j] = true;
                }
            }
        }
    }

    // ── attachments ─────────────────────────────────────────────────────────────────────────

    /**
     * Torches, signs, buttons, lanterns: whatever hangs from something takes the first support in a
     * fixed, structure-local order — north, east, south, west, then floor, then ceiling (format
     * spec) — so a turned building keeps its torches on the same walls. A lantern hangs before it
     * stands (decision: Luiz, 2026-09-26). Only nothing that can hold it refuses the plan. A property
     * the author wrote that settles it (a lantern's {@code hanging}, a sign's {@code rotation}) is
     * left alone.
     */
    private void attach(CellKind[] kinds, Outcome[] states) {
        Map<String, String> twins = wallTwins();
        for (int layer = bp.minLayer(); layer <= bp.maxLayer(); layer++) {
            for (int z = 0; z < bp.depth(); z++) {
                for (int x = 0; x < bp.width(); x++) {
                    int i = index(layer, x, z);
                    Outcome state = states[i];
                    BlockInfo info = state == null ? null : dict.block(state.block()).orElse(null);
                    if (info == null) {
                        continue;
                    }
                    String wall = twins.get(info.id());
                    BlockInfo wallInfo = wall == null ? null : dict.block(wall).orElse(null);
                    boolean twin = wallInfo != null
                            && wallInfo.properties().keySet().containsAll(state.props().keySet());
                    boolean face = info.has("face", "wall") && info.has("face", "floor") && info.has("face", "ceiling")
                            && info.properties().containsKey("facing") && !state.props().containsKey("face");
                    boolean hanging = info.has("hanging", "true") && info.has("hanging", "false")
                            && !state.props().containsKey("hanging");
                    if (!twin && !face && !hanging) {
                        continue;
                    }
                    Outcome attached = null;
                    if (hanging) {
                        attached = holds(kinds, states, layer + 1, x, z, Support.Face.DOWN, true)
                                ? with(state, "hanging", "true")
                                : holds(kinds, states, layer - 1, x, z, Support.Face.UP, true)
                                ? with(state, "hanging", "false") : null;
                    } else {
                        for (Facing side : Facing.values()) {
                            Support.Face toward = Support.Face.values()[side.opposite().ordinal()];
                            if (holds(kinds, states, layer, x + side.dx, z + side.dz, toward, false)) {
                                attached = twin ? with(new Outcome(wall, state.props()), "facing",
                                        side.opposite().word())
                                        : with(with(state, "face", "wall"), "facing", side.opposite().word());
                                break;
                            }
                        }
                        // A torch stands on the middle of a face; a button covers the whole of it.
                        if (attached == null && holds(kinds, states, layer - 1, x, z, Support.Face.UP, twin)) {
                            attached = twin ? state : with(state, "face", "floor");
                        }
                        if (attached == null && face
                                && holds(kinds, states, layer + 1, x, z, Support.Face.DOWN, false)) {
                            attached = with(state, "face", "ceiling");
                        }
                    }
                    if (attached == null) {
                        out.cellError("unattached", bp.sourceLine(layer, x, z), bp.sourceColumn(layer, x, z),
                                new Cell(layer, x, z), Ids.brief(info.id()) + " has nothing to hang from: no "
                                        + (twin ? "wall or floor" : face ? "wall, floor or ceiling"
                                        : "ceiling or floor") + " beside it that can hold it");
                        continue;
                    }
                    states[i] = attached;
                }
            }
        }
    }

    /**
     * A standing block to its wall-hung twin — {@code torch} to {@code wall_torch}, a sign to its
     * wall sign — found as the two blocks one item places. Hanging signs are left out: theirs
     * hangs from above, and the wall one is a bracket, not the same thing turned.
     */
    private Map<String, String> wallTwins() {
        Map<String, List<BlockInfo>> byItem = new HashMap<>();
        for (BlockInfo info : dict.blocks().values()) {
            if (!info.item().isEmpty()) {
                byItem.computeIfAbsent(info.item(), item -> new ArrayList<>()).add(info);
            }
        }
        Map<String, String> twins = new HashMap<>();
        for (List<BlockInfo> placed : byItem.values()) {
            if (placed.size() != 2) {
                continue;
            }
            for (int k = 0; k < 2; k++) {
                BlockInfo standing = placed.get(k);
                BlockInfo wall = placed.get(1 - k);
                List<String> facings = wall.properties().get("facing");
                if (Ids.path(wall.id()).contains("wall") && facings != null && facings.size() == 4
                        && !standing.properties().containsKey("facing")
                        && !standing.properties().containsKey("attached")) {
                    twins.put(standing.id(), wall.id());
                }
            }
        }
        return twins;
    }

    /**
     * A block whose {@code face} holds, or ground: {@code ~}, and outside the drawing or under a
     * {@code ?} at layer 0 and below, the world's own. {@code ?} above ground might be anything, so
     * it holds nothing up.
     */
    private boolean holds(CellKind[] kinds, Outcome[] states, int layer, int x, int z, Support.Face face,
                          boolean center) {
        if (!bp.contains(layer, x, z)) {
            return layer <= 0;
        }
        int i = index(layer, x, z);
        return switch (kinds[i]) {
            case TERRAIN -> true;
            case KEEP -> layer <= 0;
            case AIR -> false;
            case BLOCK -> support.holds(states[i], face, center);
        };
    }

    private static Outcome with(Outcome outcome, String key, String value) {
        Map<String, String> props = new TreeMap<>(outcome.props());
        props.put(key, value);
        return new Outcome(outcome.block(), props);
    }

    // ── the bill ────────────────────────────────────────────────────────────────────────────

    /**
     * Counted per cell, but read per legend entry from the settled bindings rather than the rolls,
     * so a mix asks for any of its members (decision: Luiz). A fixture's other part costs nothing;
     * a double slab costs two.
     */
    private List<BillLine> bill(CellKind[] kinds, Outcome[] states, boolean[] partner, Map<String, Integer> itemless) {
        Map<Character, Set<String>> itemsOf = new HashMap<>();
        Map<Set<String>, Integer> counts = new LinkedHashMap<>();
        for (int layer = bp.minLayer(); layer <= bp.maxLayer(); layer++) {
            for (int z = 0; z < bp.depth(); z++) {
                for (int x = 0; x < bp.width(); x++) {
                    int i = index(layer, x, z);
                    EntryInfo entry = bp.entryAt(layer, x, z);
                    if (kinds[i] != CellKind.BLOCK || partner[i] || entry == null) {
                        continue;
                    }
                    Set<String> items = itemsOf.computeIfAbsent(entry.glyph(), glyph -> {
                        Set<String> found = new TreeSet<>();
                        for (Outcome outcome : blocks(entries.get(glyph))) {
                            dict.block(outcome.block()).map(BlockInfo::item).filter(item -> !item.isEmpty())
                                    .ifPresent(found::add);
                        }
                        return found;
                    });
                    int count = count(states[i]);
                    if (items.isEmpty()) {
                        itemless.merge(Ids.brief(states[i].block()), count, Integer::sum);
                    } else {
                        counts.merge(items, count, Integer::sum);
                    }
                }
            }
        }
        List<BillLine> bill = new ArrayList<>();
        counts.forEach((items, count) -> bill.add(new BillLine(items, count)));
        bill.sort(Comparator.comparingInt(BillLine::count).reversed()
                .thenComparing(line -> String.join(",", line.items())));
        return bill;
    }

    private static int count(Outcome state) {
        if ("double".equals(state.props().get("type")) && Ids.path(state.block()).endsWith("_slab")) {
            return 2;
        }
        for (String key : COUNTS) {
            String value = state.props().get(key);
            if (value != null && value.matches("[0-9]+")) {
                return Integer.parseInt(value);
            }
        }
        return 1;
    }
}
