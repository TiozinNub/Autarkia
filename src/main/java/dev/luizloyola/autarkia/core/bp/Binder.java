package dev.luizloyola.autarkia.core.bp;

import dev.luizloyola.autarkia.core.bp.Blueprint.Edge;
import dev.luizloyola.autarkia.core.bp.Blueprint.EntryInfo;
import dev.luizloyola.autarkia.core.bp.Blueprint.Facing;
import dev.luizloyola.autarkia.core.bp.Blueprint.Headers;
import dev.luizloyola.autarkia.core.bp.Blueprint.Node;
import dev.luizloyola.autarkia.core.bp.Blueprint.Outcome;
import dev.luizloyola.autarkia.core.bp.Blueprint.SlotInfo;
import dev.luizloyola.autarkia.core.bp.Blueprint.SlotKind;
import dev.luizloyola.autarkia.core.bp.BpSource.Entry;
import dev.luizloyola.autarkia.core.bp.BpSource.Grid;
import dev.luizloyola.autarkia.core.bp.BpSource.GroupDecl;
import dev.luizloyola.autarkia.core.bp.BpSource.Header;
import dev.luizloyola.autarkia.core.bp.BpSource.Named;
import dev.luizloyola.autarkia.core.bp.BpSource.NeedDecl;
import dev.luizloyola.autarkia.core.bp.BpSource.NodeDecl;
import dev.luizloyola.autarkia.core.bp.BpSource.PathChain;
import dev.luizloyola.autarkia.core.bp.BpSource.Row;
import dev.luizloyola.autarkia.core.bp.BpSource.Slot;
import dev.luizloyola.autarkia.core.bp.BpSource.SlotRef;
import dev.luizloyola.autarkia.core.bp.BpSource.Term;
import dev.luizloyola.autarkia.core.bp.Diagnostic.Cell;
import dev.luizloyola.autarkia.core.bp.Dictionary.BlockInfo;
import dev.luizloyola.autarkia.core.bp.Variants.Overlay;
import dev.luizloyola.autarkia.core.bp.Variants.Selection;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;

/**
 * Stage 2: {@link BpSource} + {@link Dictionary} to {@link Blueprint}. Nearly every load-time error
 * lives here — structure (rows, layers, glyphs, fixtures, nodes, paths) and vocabulary (slots,
 * forms, properties, and the narrowing that rejects a material no member of which can supply
 * every form the file asks of it).
 */
public final class Binder {

    /** Every entry costs a glyph; raising it is a bp 2 question (Luiz). */
    public static final int LEGEND_CAP = 64;

    /** Every allowed selection is checked in full, so past this many a file is refused, not half-checked. */
    public static final int SELECTION_CAP = 256;

    private static final List<String> REQUIRED = List.of("name", "author", "version", "orientation", "flippable");
    private static final Set<String> KNOWN_HEADERS = Set.of("name", "author", "version", "orientation",
            "flippable", "size");

    private final String id;
    private final BpSource src;
    private final Dictionary dict;
    private final Diagnostics out;

    /** Every slot, keyed by number; the working state behind {@link SlotInfo}. */
    private final Map<Integer, SlotState> slots = new LinkedHashMap<>();
    private final Map<Character, EntryInfo> legend = new LinkedHashMap<>();

    private static final class SlotState {
        final Slot slot;
        final SlotKind kind;
        final int order;
        final Set<Demand> demands = new LinkedHashSet<>();
        Set<String> declared = Set.of();
        Set<String> domain = Set.of();
        List<Outcome> outcomes = List.of();
        boolean broken;

        SlotState(Slot slot, SlotKind kind, int order) {
            this.slot = slot;
            this.kind = kind;
            this.order = order;
        }
    }

    /** What one use of a material slot asks of its members: a form, and properties on its block. */
    private record Demand(String form, Map<String, String> props) {
    }

    private Binder(String id, BpSource src, Dictionary dict, Diagnostics out) {
        this.id = id;
        this.src = src;
        this.dict = dict;
        this.out = out;
    }

    /** The bound blueprint, or null when anything was an error — every error reported either way. */
    public static @Nullable Blueprint bind(String id, BpSource src, Dictionary dict, Diagnostics out) {
        return new Binder(id, src, dict, out).bind();
    }

    private @Nullable Blueprint bind() {
        Headers headers = headers();
        slots();
        legend();
        GroupModel groups = groups();
        GridResult grids = grids(groups);
        Map<String, Node> nodes = grids == null ? Map.of() : nodes(grids);
        List<Edge> edges = paths(nodes);
        List<Selection> selections = List.of();
        if (grids != null) {
            selections = selections(groups, grids);
            Map<Character, Part> parts = fixtureParts();
            Variants model = new Variants(groups.list, groups.needs, grids.overlays, selections);
            Variants.acrossSelections(selections, (selection, each) -> fixtures(parts, Variants.compose(grids.cells,
                    grids.line, grids.column, model.ordered(selection)), grids, each), out);
            size(grids);
        }
        unused(grids);
        if (out.hasErrors() || headers == null || grids == null) {
            return null;
        }
        List<SlotInfo> slotInfos = new ArrayList<>();
        for (SlotState state : ordered()) {
            slotInfos.add(new SlotInfo(state.slot.number(), state.kind, state.slot.binding(),
                    BpText.union(state.slot.binding(), state.slot.terms()), state.slot.terms(), state.declared,
                    state.domain,
                    state.demands.stream().map(Demand::form).collect(Collectors.toCollection(TreeSet::new)),
                    state.outcomes, state.slot.line()));
        }
        Variants variants = groups.list.isEmpty() ? Variants.NONE
                : new Variants(groups.list, groups.needs, grids.overlays, selections);
        return new Blueprint(id, headers, grids.width, grids.depth, grids.minLayer, grids.cells, grids.line,
                grids.column, grids.baseMin, grids.baseMax, slotInfos, legend, nodes, edges, variants, Selection.BASE);
    }

    // ── headers ─────────────────────────────────────────────────────────────────────────────

    private @Nullable Headers headers() {
        if (src.format() == 0) {
            return null;
        }
        Map<String, Header> byKey = new HashMap<>();
        for (Header header : src.headers()) {
            Header was = byKey.putIfAbsent(header.key(), header);
            if (was != null) {
                out.error("header_twice", header.line(), 1, "header '" + header.key() + "' is already given on line "
                        + was.line());
            } else if (!KNOWN_HEADERS.contains(header.key())) {
                out.report("header_unknown", header.line(), 1, "unknown header '" + header.key() + "' is kept but "
                        + "means nothing yet" + Suggest.hint(header.key(), KNOWN_HEADERS));
            }
        }
        boolean complete = true;
        for (String key : REQUIRED) {
            if (!byKey.containsKey(key)) {
                out.error("header_missing", 0, 0, "header '" + key + "' is required and has no default");
                complete = false;
            }
        }
        int version = 0;
        Header versionHeader = byKey.get("version");
        if (versionHeader != null) {
            if (versionHeader.value().matches("[0-9]+") && Integer.parseInt(versionHeader.value()) > 0) {
                version = Integer.parseInt(versionHeader.value());
            } else {
                out.error("header_version", versionHeader.line(), 1, "version is the structure's own revision, a "
                        + "whole number from 1; bump it on edit");
                complete = false;
            }
        }
        Set<Facing> orientation = EnumSet.noneOf(Facing.class);
        Header orientationHeader = byKey.get("orientation");
        if (orientationHeader != null) {
            for (String word : orientationHeader.value().split("[\\s,]+")) {
                Facing facing = Facing.of(word);
                if (word.equals("all")) {
                    orientation.addAll(EnumSet.allOf(Facing.class));
                } else if (facing != null) {
                    orientation.add(facing);
                } else {
                    out.error("header_orientation", orientationHeader.line(), 1, "orientation is 'all' or some of "
                            + "north, east, south, west — not '" + word + "'");
                    complete = false;
                }
            }
        }
        boolean flippable = false;
        Header flipHeader = byKey.get("flippable");
        if (flipHeader != null) {
            switch (flipHeader.value()) {
                case "true" -> flippable = true;
                case "false" -> flippable = false;
                default -> {
                    out.error("header_flippable", flipHeader.line(), 1, "flippable is true or false");
                    complete = false;
                }
            }
        }
        if (!complete) {
            return null;
        }
        return new Headers(Objects.requireNonNull(byKey.get("name")).value(),
                Objects.requireNonNull(byKey.get("author")).value(), version, orientation, flippable);
    }

    /** {@code size <x> <z> <y>}, the one optional header: a self-check against the grids. */
    private void size(GridResult grids) {
        for (Header header : src.headers()) {
            if (!header.key().equals("size")) {
                continue;
            }
            String[] words = header.value().split("\\s+");
            int layers = grids.cells.length;
            if (words.length != 3 || !words[0].matches("[0-9]+") || !words[1].matches("[0-9]+")
                    || !words[2].matches("[0-9]+")) {
                out.error("header_size", header.line(), 1, "size is '<x> <z> <y>', three whole numbers");
            } else if (Integer.parseInt(words[0]) != grids.width || Integer.parseInt(words[1]) != grids.depth
                    || Integer.parseInt(words[2]) != layers) {
                out.error("size_mismatch", header.line(), 1, "size says " + header.value() + " but the grids are "
                        + grids.width + " " + grids.depth + " " + layers);
            }
            return;
        }
    }

    // ── slots ───────────────────────────────────────────────────────────────────────────────

    private List<SlotState> ordered() {
        List<SlotState> ordered = new ArrayList<>(slots.values());
        ordered.sort(Comparator.comparingInt(state -> state.order));
        return ordered;
    }

    private void slots() {
        List<Slot> all = new ArrayList<>();
        Map<Slot, SlotKind> kinds = new HashMap<>();
        for (Slot slot : src.materials()) {
            all.add(slot);
            kinds.put(slot, SlotKind.MATERIAL);
        }
        for (Slot slot : src.palette()) {
            all.add(slot);
            kinds.put(slot, SlotKind.PALETTE);
        }
        all.sort(Comparator.comparingInt(Slot::line));
        for (int i = 0; i < all.size(); i++) {
            Slot slot = all.get(i);
            SlotState was = slots.get(slot.number());
            if (was != null) {
                out.error("slot_twice", slot.line(), slot.column(), "slot " + slot.number() + " is already defined on "
                        + "line " + was.slot.line() + "; one number space covers materials and palette");
                continue;
            }
            slots.put(slot.number(), new SlotState(slot, kinds.get(slot), i));
        }
        // Types and references first, so demands can be gathered from uses that come later.
        for (SlotState state : ordered()) {
            for (Term term : state.slot.terms()) {
                if (!checkTerm(term, state)) {
                    state.broken = true;
                }
            }
        }
        for (Entry entry : src.legend()) {
            for (Term term : entry.terms()) {
                checkTerm(term, null);
            }
        }
        gatherDemands();
        for (SlotState state : ordered()) {
            if (state.kind == SlotKind.MATERIAL) {
                narrow(state);
            } else {
                state.outcomes = outcomes(state.slot.terms(), state.slot.line());
                state.declared = state.outcomes.stream().map(Outcome::block)
                        .collect(Collectors.toCollection(LinkedHashSet::new));
                state.domain = state.declared;
            }
            checkBinding(state.slot.binding(), state.slot.terms(), outcomeCount(state), state.slot.line(),
                    state.slot.column(), "slot " + state.slot.number(), state.kind == SlotKind.MATERIAL, false);
        }
    }

    /** Lint, so said only once the file has nothing worse to say. */
    private void unused(@Nullable GridResult grids) {
        if (out.hasErrors()) {
            return;
        }
        for (SlotState state : ordered()) {
            if (!used(state.slot.number())) {
                out.report("unused_slot", state.slot.line(), state.slot.column(), "slot " + state.slot.number()
                        + " is never referenced");
            }
        }
        if (grids != null) {
            unusedGlyphs(grids);
            grids.overlays.forEach((key, overlay) -> {
                if (changesNothing(overlay)) {
                    int[] at = grids.declared.get(key);
                    out.report("variant_empty", at[0], at[1], "'" + key + "' draws nothing, so choosing it changes "
                            + "nothing");
                }
            });
        }
    }

    private int outcomeCount(SlotState state) {
        return state.kind == SlotKind.MATERIAL ? state.domain.size() : state.outcomes.size();
    }

    /**
     * Kind and order of one term. {@code in} is the slot the term sits in, or null for the legend,
     * which may reference any slot.
     */
    private boolean checkTerm(Term term, @Nullable SlotState in) {
        boolean inMaterials = in != null && in.kind == SlotKind.MATERIAL;
        if (term instanceof Named named) {
            String qualified = Ids.qualify(named.name());
            if (inMaterials) {
                if (dict.material(qualified).isPresent()) {
                    return true;
                }
                if (dict.block(qualified).isPresent()) {
                    out.error("material_is_block", term.line(), term.column(), "'" + named.name() + "' is a block; "
                            + "materials holds groups like oak or any_color, and the palette holds blocks");
                } else {
                    out.error("unknown_material", term.line(), term.column(), "no material '" + named.name() + "'"
                            + Suggest.hint(Ids.brief(qualified), briefMaterials()));
                }
                return false;
            }
            return true;
        }
        SlotRef ref = (SlotRef) term;
        SlotState target = slots.get(ref.slot());
        if (target == null) {
            if (!src.brokenSlots().contains(ref.slot())) {
                out.error("unknown_slot", term.line(), term.column(), "no slot " + ref.slot());
            }
            return false;
        }
        if (in != null && target.order >= in.order) {
            out.error("forward_ref", term.line(), term.column(), target == in
                    ? "slot " + ref.slot() + " refers to itself"
                    : "slot " + ref.slot() + " is defined below; a reference points only backwards");
            return false;
        }
        if (ref.form() != null && !dict.forms().contains(ref.form())) {
            out.error("unknown_form", term.line(), term.column(), "no form '" + ref.form() + "'"
                    + Suggest.hint(ref.form(), dict.forms()));
            return false;
        }
        if (inMaterials) {
            if (target.kind != SlotKind.MATERIAL) {
                out.error("slot_kind", term.line(), term.column(), "slot " + ref.slot() + " is a palette; a material "
                        + "slot takes materials");
                return false;
            }
            if (ref.form() != null) {
                out.error("form_in_materials", term.line(), term.column(), "'$" + ref.slot() + " " + ref.form()
                        + "' is a block; it belongs in the palette or the legend");
                return false;
            }
            return true;
        }
        if (target.kind == SlotKind.MATERIAL && ref.form() == null) {
            out.error("form_missing", term.line(), term.column(), "slot " + ref.slot() + " is a material, not a "
                    + "block; name a form, like '$" + ref.slot() + " planks'");
            return false;
        }
        if (target.kind == SlotKind.PALETTE && ref.form() != null) {
            out.error("slot_kind", term.line(), term.column(), "slot " + ref.slot() + " is a palette, not a material; "
                    + "'$" + ref.slot() + " " + ref.form() + "' has nothing to take a form of");
            return false;
        }
        return true;
    }

    /** Every form asked of a material slot, carried back through the material slots that name it. */
    private void gatherDemands() {
        for (SlotState state : ordered()) {
            if (state.kind == SlotKind.PALETTE) {
                demandsOf(state.slot.terms());
            }
        }
        for (Entry entry : src.legend()) {
            demandsOf(entry.terms());
        }
        List<SlotState> reversed = ordered();
        java.util.Collections.reverse(reversed);
        for (SlotState state : reversed) {
            if (state.kind != SlotKind.MATERIAL) {
                continue;
            }
            for (Term term : state.slot.terms()) {
                if (term instanceof SlotRef ref) {
                    SlotState target = slots.get(ref.slot());
                    if (target != null && target != state) {
                        target.demands.addAll(state.demands);
                    }
                }
            }
        }
    }

    private void demandsOf(List<Term> terms) {
        for (Term term : terms) {
            if (term instanceof SlotRef ref && ref.form() != null && dict.forms().contains(ref.form())) {
                SlotState target = slots.get(ref.slot());
                if (target != null && target.kind == SlotKind.MATERIAL) {
                    target.demands.add(new Demand(ref.form(), ref.props()));
                }
            }
        }
    }

    /** Narrowing: keep the members whose block answers every demand; none left is a load error. */
    private void narrow(SlotState state) {
        Set<String> declared = new LinkedHashSet<>();
        for (Term term : state.slot.terms()) {
            if (term instanceof Named named) {
                declared.addAll(dict.leaves(Ids.qualify(named.name())));
            } else if (term instanceof SlotRef ref) {
                SlotState target = slots.get(ref.slot());
                if (target != null) {
                    declared.addAll(target.domain);
                }
            }
        }
        state.declared = declared;
        // A property no member's block has at all is the term's error to report, not a reason to narrow.
        Set<Demand> narrowing = new LinkedHashSet<>();
        for (Demand demand : state.demands) {
            boolean somebody = demand.props().isEmpty() || declared.stream().anyMatch(member ->
                    dict.lookup(member, demand.form()).flatMap(dict::block).map(info -> demand.props().entrySet()
                            .stream().allMatch(p -> info.has(p.getKey(), p.getValue()))).orElse(false));
            boolean formSomewhere = declared.stream().anyMatch(m -> dict.lookup(m, demand.form()).isPresent());
            if (somebody || !formSomewhere) {
                narrowing.add(demand);
            }
        }
        Set<String> domain = new LinkedHashSet<>();
        Map<String, Set<String>> missing = new TreeMap<>();
        for (String member : declared) {
            boolean keeps = true;
            for (Demand demand : narrowing) {
                Optional<String> block = dict.lookup(member, demand.form());
                String why = null;
                if (block.isEmpty()) {
                    why = demand.form();
                } else {
                    BlockInfo info = dict.block(block.get()).orElse(null);
                    for (Map.Entry<String, String> prop : demand.props().entrySet()) {
                        if (info == null || !info.has(prop.getKey(), prop.getValue())) {
                            why = demand.form() + "[" + prop.getKey() + "=" + prop.getValue() + "]";
                            break;
                        }
                    }
                }
                if (why != null) {
                    missing.computeIfAbsent(why, w -> new TreeSet<>()).add(Ids.brief(member));
                    keeps = false;
                }
            }
            if (keeps) {
                domain.add(member);
            }
        }
        state.domain = domain;
        if (domain.isEmpty() && !declared.isEmpty() && !state.broken) {
            String reasons = missing.entrySet().stream()
                    .map(entry -> entry.getValue().size() == declared.size()
                            ? "no member has " + entry.getKey()
                            : entry.getValue().size() + " lack " + entry.getKey())
                    .collect(Collectors.joining("; "));
            out.error("empty_domain", state.slot.line(), state.slot.column(), "slot " + state.slot.number()
                    + ": no member can supply every form this file asks of it — " + reasons);
        }
    }

    private boolean used(int number) {
        for (SlotState state : slots.values()) {
            for (Term term : state.slot.terms()) {
                if (term instanceof SlotRef ref && ref.slot() == number) {
                    return true;
                }
            }
        }
        for (Entry entry : src.legend()) {
            for (Term term : entry.terms()) {
                if (term instanceof SlotRef ref && ref.slot() == number) {
                    return true;
                }
            }
        }
        return false;
    }

    // ── block expressions ───────────────────────────────────────────────────────────────────

    /** Every block a union can produce, in order, each with its term's properties checked. */
    private List<Outcome> outcomes(List<Term> terms, int line) {
        Map<Outcome, Boolean> found = new LinkedHashMap<>();
        for (Term term : terms) {
            for (Outcome outcome : outcomesOf(term)) {
                found.putIfAbsent(outcome, Boolean.TRUE);
            }
        }
        List<Outcome> outcomes = new ArrayList<>(found.keySet());
        checkProps(terms, line);
        return outcomes;
    }

    private List<Outcome> outcomesOf(Term term) {
        if (term instanceof Named named) {
            String block = Ids.qualify(named.name());
            if (dict.block(block).isEmpty()) {
                return List.of();
            }
            return List.of(new Outcome(block, named.props()));
        }
        SlotRef ref = (SlotRef) term;
        SlotState target = slots.get(ref.slot());
        if (target == null) {
            return List.of();
        }
        List<Outcome> outcomes = new ArrayList<>();
        if (target.kind == SlotKind.PALETTE && ref.form() == null) {
            for (Outcome outcome : target.outcomes) {
                Map<String, String> props = new TreeMap<>(outcome.props());
                props.putAll(ref.props());
                outcomes.add(new Outcome(outcome.block(), props));
            }
        } else if (target.kind == SlotKind.MATERIAL && ref.form() != null) {
            for (String member : target.domain) {
                dict.lookup(member, ref.form()).ifPresent(block -> outcomes.add(new Outcome(block, ref.props())));
            }
        }
        return outcomes;
    }

    /** Unknown blocks, and properties the blocks do not have or should not be told. */
    private void checkProps(List<Term> terms, int line) {
        for (Term term : terms) {
            if (term instanceof Named named) {
                String block = Ids.qualify(named.name());
                Optional<BlockInfo> info = dict.block(block);
                if (info.isEmpty()) {
                    String hint = dict.material(block).isPresent()
                            ? " — '" + named.name() + "' is a material; reach it through a slot, like "
                                    + "'materials 1 option " + named.name() + "' and '$1 planks'"
                            : Suggest.hint(Ids.brief(block), briefBlocks());
                    out.error("unknown_block", term.line(), term.column(), "no block '" + named.name() + "'" + hint);
                    continue;
                }
                checkPropsOn(List.of(info.get()), term);
            } else {
                List<BlockInfo> infos = new ArrayList<>();
                for (Outcome outcome : outcomesOf(term)) {
                    dict.block(outcome.block()).ifPresent(infos::add);
                }
                checkPropsOn(infos, term);
            }
        }
    }

    private void checkPropsOn(List<BlockInfo> blocks, Term term) {
        for (Map.Entry<String, String> prop : new TreeMap<>(term.props()).entrySet()) {
            String key = prop.getKey();
            String value = prop.getValue();
            if (key.equals(Planner.ATTACH)) {
                checkAttach(blocks, term, value);
                continue;
            }
            for (BlockInfo block : blocks) {
                List<String> values = block.properties().get(key);
                if (values == null) {
                    out.error("unknown_property", term.line(), term.column(), Ids.brief(block.id()) + " has no "
                            + "property '" + key + "'" + (block.properties().isEmpty() ? "; it has none"
                            : Suggest.hint(key, block.properties().keySet())));
                    return;
                }
                if (!values.contains(value)) {
                    out.error("property_value", term.line(), term.column(), Ids.brief(block.id()) + "'s " + key
                            + " is one of " + String.join(", ", values) + ", not '" + value + "'");
                    return;
                }
            }
            if (!blocks.isEmpty() && computed(blocks.get(0).id(), key, value)) {
                out.report("computed_property", term.line(), term.column(), key + "=" + value + " is the world's "
                        + "to set, not the builder's; leave it out");
            }
        }
    }

    /** {@code attach=floor} settles a block that would otherwise hang on a wall beside it, and only that. */
    private void checkAttach(List<BlockInfo> blocks, Term term, String value) {
        if (!value.equals("floor")) {
            out.error("attach_value", term.line(), term.column(), "attach is 'floor', the one thing a wall-hung "
                    + "twin cannot say; a wall is said by naming the wall block, like wall_torch[facing=south]");
            return;
        }
        for (BlockInfo block : blocks) {
            if (!dict.wallTwins().containsKey(block.id())) {
                out.error("attach_block", term.line(), term.column(), Ids.brief(block.id()) + " never hangs on a "
                        + "wall, so attach settles nothing" + (block.properties().containsKey("hanging")
                        ? "; a lantern says hanging=true or hanging=false" : block.properties().containsKey("face")
                        ? "; a button or lever says face=floor, wall or ceiling" : ""));
                return;
            }
        }
    }

    /** Set by the world on every block that has them: joins from the neighbours, power, growth, use. */
    private static final Set<String> WORLD_STATE = Set.of("snowy", "in_wall", "side_chain", "leaves", "tip",
            "thickness", "bottom", "distance", "signal_fire", "drag", "power", "enabled", "locked", "triggered",
            "extended", "crafting", "moisture", "age", "stage", "berries", "hatch", "honey_level", "occupied",
            "disarmed", "tilt", "dusted", "bloom", "sculk_sensor_phase", "shrieking", "can_summon", "natural",
            "unstable", "vault_state", "trial_spawner_state", "creaking_heart_state", "hydration");

    /**
     * What the world sets rather than the builder, so a file need not say it and a capture does not:
     * joins recomputed from the neighbours, and state the game changes on its own. What a builder can
     * set stays — a door left open, a lever thrown, a candle lit (Luiz, 2026-09-27).
     */
    static boolean computed(String block, String key, String value) {
        String path = Ids.path(block);
        if (WORLD_STATE.contains(key)) {
            return true;
        }
        return switch (key) {
            case "waterlogged" -> value.equals("false");
            case "north", "east", "south", "west", "up" -> path.endsWith("_fence") || path.endsWith("_wall")
                    || path.endsWith("_pane") || path.endsWith("_bars") || path.equals("iron_bars")
                    || path.equals("redstone_wire") || path.equals("tripwire");
            case "shape" -> path.endsWith("_stairs");
            case "persistent" -> path.endsWith("_leaves");
            case "powered" -> !path.equals("lever");
            case "attached" -> path.startsWith("tripwire");
            case "level" -> path.equals("water") || path.equals("lava");
            case "lit" -> path.equals("furnace") || path.equals("blast_furnace") || path.equals("smoker")
                    || path.startsWith("redstone_") || path.endsWith("redstone_ore") || path.endsWith("copper_bulb");
            default -> false;
        };
    }

    /**
     * {@code mix} over an outcome that cannot vary is an error, and {@code mix} over a slot that
     * already mixes adds nothing. A slot always states its binding; a legend entry that is one
     * constant term is told its {@code option} binds nothing.
     */
    private void checkBinding(@Nullable Binding binding, List<Term> terms, int outcomes, int line, int column,
                              String what, boolean materials, boolean legendEntry) {
        if (binding == null) {
            if (terms.size() > 1) {
                out.error("binding_missing", line, column, what + " is a union; say 'option' (chosen once) or 'mix' "
                        + "(per cell)");
            }
            return;
        }
        boolean single = terms.size() == 1;
        boolean refToMix = single && terms.get(0) instanceof SlotRef ref && isMix(ref.slot());
        boolean constant = outcomes <= 1 || single && constantTerm(terms.get(0), materials);
        if (binding == Binding.MIX) {
            if (refToMix) {
                out.report("mix_noop", line, column, what + ": that slot already mixes per cell; 'mix' adds "
                        + "nothing");
            } else if (constant) {
                out.error("mix_constant", line, column, what + " cannot vary; there is nothing to mix");
            }
        } else if (legendEntry && constant) {
            out.report("binding_unneeded", line, column, what + " is one block; 'option' binds nothing");
        }
    }

    /**
     * A term whose outcome is settled once planned: a block, a single material, or a reference to
     * an {@code option} slot, which collapses. A reference to a {@code mix} slot rolls per cell.
     */
    private boolean constantTerm(Term term, boolean materials) {
        if (term instanceof Named named) {
            return !materials || dict.leaves(Ids.qualify(named.name())).size() <= 1;
        }
        return !isMix(((SlotRef) term).slot());
    }

    private boolean isMix(int slot) {
        SlotState state = slots.get(slot);
        return state != null && state.slot.binding() == Binding.MIX;
    }

    // ── groups ──────────────────────────────────────────────────────────────────────────────

    /** The groups as declared, what each variant needs, and where each variant was named. */
    private static final class GroupModel {
        final List<Variants.Group> list = new ArrayList<>();
        final Map<String, Set<String>> needs = new LinkedHashMap<>();
        /** {@code group.variant} to its {line, column} in 'groups'. */
        final Map<String, int[]> declared = new LinkedHashMap<>();
        /** Every {@code group.variant} written in 'groups', broken lines included, so none is reported twice. */
        final Set<String> named = new HashSet<>();
        boolean broken;
    }

    private GroupModel groups() {
        GroupModel model = new GroupModel();
        Set<String> names = new HashSet<>();
        for (GroupDecl decl : src.groups()) {
            decl.variants().forEach(variant -> model.named.add(decl.name() + "." + variant));
            if (!names.add(decl.name())) {
                out.error("group_twice", decl.line(), decl.column(), "group '" + decl.name() + "' is already declared");
                model.broken = true;
                continue;
            }
            List<String> variants = new ArrayList<>();
            for (int i = 0; i < decl.variants().size(); i++) {
                String variant = decl.variants().get(i);
                if (variants.contains(variant)) {
                    out.error("variant_twice", decl.line(), decl.columns().get(i), "'" + variant + "' is listed twice "
                            + "in group '" + decl.name() + "'");
                    model.broken = true;
                    continue;
                }
                variants.add(variant);
                model.declared.put(decl.name() + "." + variant, new int[] {decl.line(), decl.columns().get(i)});
            }
            model.list.add(new Variants.Group(decl.name(), decl.required(), variants, decl.line()));
        }
        Map<String, NeedDecl> firstNeed = new HashMap<>();
        for (NeedDecl need : src.needs()) {
            if (!model.declared.containsKey(need.variant())) {
                out.error("unknown_variant", need.line(), need.column(), "no variant '" + need.variant() + "' in "
                        + "'groups'" + Suggest.hint(need.variant(), model.declared.keySet()));
                model.broken = true;
                continue;
            }
            firstNeed.putIfAbsent(need.variant(), need);
            for (int i = 0; i < need.targets().size(); i++) {
                String target = need.targets().get(i);
                if (!model.declared.containsKey(target)) {
                    out.error("unknown_variant", need.line(), need.columns().get(i), "no variant '" + target + "' in "
                            + "'groups'" + Suggest.hint(target, model.declared.keySet()));
                    model.broken = true;
                } else if (groupOf(target).equals(groupOf(need.variant()))) {
                    out.error("needs_own_group", need.line(), need.columns().get(i), "'" + need.variant() + "' and '"
                            + target + "' are variants of one group, which are never chosen together");
                    model.broken = true;
                } else {
                    model.needs.computeIfAbsent(need.variant(), key -> new LinkedHashSet<>()).add(target);
                }
            }
        }
        for (String key : model.needs.keySet()) {
            if (Variants.reaches(model.needs, key, key)) {
                NeedDecl need = firstNeed.get(key);
                out.error("needs_cycle", need.line(), need.column(), "'" + key + "' needs itself, through what it "
                        + "needs; one of them has to come first");
                model.broken = true;
                break;
            }
        }
        return model;
    }

    private static String groupOf(String key) {
        return key.substring(0, key.indexOf('.'));
    }

    /**
     * Every selection the groups allow — the file's only one, the base, when it has none. Refuses a
     * variant no selection can hold, and two variants that change one cell with no need between
     * them, whose result would hang on an order nobody wrote.
     */
    private List<Selection> selections(GroupModel groups, GridResult grids) {
        if (groups.list.isEmpty()) {
            return List.of(Selection.BASE);
        }
        if (groups.broken) {
            return List.of();
        }
        int line = src.groups().get(0).line();
        Optional<List<Selection>> all = Variants.enumerate(groups.list, groups.needs, 16 * SELECTION_CAP);
        if (all.isEmpty() || all.get().size() > SELECTION_CAP) {
            out.error("variants_too_many", line, 1, "these groups allow more than " + SELECTION_CAP + " selections, "
                    + "and every one is checked; split the blueprint, or tie variants together with needs");
            return List.of();
        }
        List<Selection> allowed = all.get();
        if (allowed.isEmpty()) {
            out.error("no_selection", line, 1, "no selection keeps every need: a required group's variants all need "
                    + "something that cannot be chosen with them");
            return List.of();
        }
        Set<String> chosenSomewhere = new HashSet<>();
        allowed.forEach(selection -> chosenSomewhere.addAll(selection.keys()));
        boolean unreachable = false;
        for (Map.Entry<String, int[]> declared : groups.declared.entrySet()) {
            if (!chosenSomewhere.contains(declared.getKey())) {
                out.error("variant_unreachable", declared.getValue()[0], declared.getValue()[1], "'"
                        + declared.getKey() + "' is in no selection: what it needs cannot be chosen with it");
                unreachable = true;
            }
        }
        List<String> keys = new ArrayList<>(groups.declared.keySet());
        for (int i = 0; i < keys.size(); i++) {
            for (int j = i + 1; j < keys.size(); j++) {
                String a = keys.get(i);
                String b = keys.get(j);
                if (groupOf(a).equals(groupOf(b)) || Variants.reaches(groups.needs, a, b)
                        || Variants.reaches(groups.needs, b, a)
                        || allowed.stream().noneMatch(s -> s.keys().contains(a) && s.keys().contains(b))) {
                    continue;
                }
                overlap(grids.overlays.get(a), grids.overlays.get(b), grids);
            }
        }
        return unreachable ? List.of() : allowed;
    }

    /** The first cell both change, reported where the second drew it. */
    private void overlap(Overlay a, Overlay b, GridResult box) {
        for (int l = 0; l < box.cells.length; l++) {
            for (int z = 0; z < box.depth; z++) {
                for (int x = 0; x < box.width; x++) {
                    if (a.cells()[l][z][x] != Variants.UNCHANGED && b.cells()[l][z][x] != Variants.UNCHANGED) {
                        out.cellError("variant_overlap", b.line()[l][z][x], b.column()[l][z][x],
                                new Cell(l + box.minLayer, x, z), "'" + a.key() + "' and '" + b.key() + "' both "
                                        + "change this cell and neither needs the other; a need says which is laid "
                                        + "over which");
                        return;
                    }
                }
            }
        }
    }

    private static boolean changesNothing(Overlay overlay) {
        for (char[][] layer : overlay.cells()) {
            for (char[] row : layer) {
                for (char glyph : row) {
                    if (glyph != Variants.UNCHANGED) {
                        return false;
                    }
                }
            }
        }
        return true;
    }

    // ── the legend ──────────────────────────────────────────────────────────────────────────

    private void legend() {
        for (Entry entry : src.legend()) {
            if (legend.containsKey(entry.glyph())) {
                out.error("glyph_twice", entry.line(), entry.column(), "glyph '" + entry.glyph() + "' is already in "
                        + "the legend on line " + Objects.requireNonNull(legend.get(entry.glyph())).line());
                continue;
            }
            if (legend.size() == LEGEND_CAP) {
                out.error("legend_full", entry.line(), entry.column(), "the legend holds at most " + LEGEND_CAP
                        + " entries");
                continue;
            }
            List<Outcome> outcomes = outcomes(entry.terms(), entry.line());
            checkBinding(entry.binding(), entry.terms(), outcomes.size(), entry.line(), entry.column(),
                    "'" + entry.glyph() + "'", false, true);
            legend.put(entry.glyph(), new EntryInfo(entry.glyph(), entry.binding(),
                    BpText.union(entry.binding(), entry.terms()), entry.terms(), outcomes, entry.line()));
        }
    }

    // ── grids ───────────────────────────────────────────────────────────────────────────────

    private static final class GridResult {
        int width;
        int depth;
        /** The box's first layer: the base's, or lower where a variant draws below it. */
        int minLayer;
        int baseMin;
        int baseMax;
        /** The base over the whole box, {@code ?} on a layer only variants draw. */
        char[][][] cells = new char[0][0][0];
        /** Where each cell was drawn, by [layer - min][z][x], for diagnostics. */
        int[][][] line = new int[0][0][0];
        int[][][] column = new int[0][0][0];
        /** Every declared variant's overlay, in the order the groups list them, drawn or not. */
        final Map<String, Overlay> overlays = new LinkedHashMap<>();
        Map<String, int[]> declared = Map.of();
        final List<PlacedNode> placed = new ArrayList<>();
    }

    private record PlacedNode(String id, int layer, int x, int z, boolean inline, int line, int column) {
    }

    private @Nullable GridResult grids(GroupModel groups) {
        List<Grid> blockGrids = src.grids().stream().filter(grid -> !grid.nodes()).toList();
        List<Grid> baseGrids = blockGrids.stream().filter(grid -> grid.variant() == null).toList();
        if (baseGrids.isEmpty() || blockGrids.get(0).rows().isEmpty()) {
            if (src.format() != 0) {
                out.error("no_layers", 0, 0, "a blueprint needs at least one 'layer' grid of its own; variants only "
                        + "draw over it");
            }
            return null;
        }
        GridResult result = new GridResult();
        result.declared = groups.declared;
        result.width = blockGrids.get(0).rows().get(0).cells().length();
        result.depth = blockGrids.get(0).rows().size();
        for (Grid grid : blockGrids) {
            glyphs(grid);
        }
        boolean shaped = true;
        for (Grid grid : src.grids()) {
            shaped &= shape(grid, result.width, result.depth);
        }
        TreeMap<Integer, Integer> claimed = new TreeMap<>();
        for (Grid grid : baseGrids) {
            for (int index : grid.indices()) {
                Integer was = claimed.putIfAbsent(index, grid.line());
                if (was != null) {
                    out.error("layer_twice", grid.line(), 1, "layer " + index + " is already drawn by the grid on "
                            + "line " + was);
                    shaped = false;
                }
            }
        }
        if (claimed.isEmpty()) {
            return null;
        }
        int min = claimed.firstKey();
        int max = claimed.lastKey();
        List<Integer> missing = new ArrayList<>();
        for (int layer = min; layer <= max; layer++) {
            if (!claimed.containsKey(layer)) {
                missing.add(layer);
            }
        }
        if (!missing.isEmpty()) {
            out.error("layer_gap", 0, 0, (missing.size() == 1 ? "layer " : "layers ") + BpText.indexSet(missing)
                    + " never drawn; every layer from " + min + " to " + max + " needs a grid");
            shaped = false;
        }
        // A variant may draw past the base — a storey above, a cellar below — so the box is every layer drawn.
        int boxMin = min;
        int boxMax = max;
        Map<String, Map<Integer, Integer>> variantClaims = new HashMap<>();
        for (Grid grid : blockGrids) {
            if (grid.variant() == null) {
                continue;
            }
            if (!groups.declared.containsKey(grid.variant())) {
                if (!groups.named.contains(grid.variant())) {
                    out.error("unknown_variant", grid.line(), grid.variantColumn(), "no variant '" + grid.variant()
                            + "' in 'groups'" + Suggest.hint(grid.variant(), groups.declared.keySet()));
                }
                shaped = false;
                continue;
            }
            Map<Integer, Integer> mine = variantClaims.computeIfAbsent(grid.variant(), key -> new HashMap<>());
            for (int index : grid.indices()) {
                Integer was = mine.putIfAbsent(index, grid.line());
                if (was != null) {
                    out.error("layer_twice", grid.line(), 1, "layer " + index + " of " + grid.variant()
                            + " is already drawn by the grid on line " + was);
                    shaped = false;
                }
                boxMin = Math.min(boxMin, index);
                boxMax = Math.max(boxMax, index);
            }
        }
        if (!shaped) {
            return null;
        }
        int span = boxMax - boxMin + 1;
        result.minLayer = boxMin;
        result.baseMin = min;
        result.baseMax = max;
        result.cells = new char[span][result.depth][result.width];
        result.line = new int[span][result.depth][result.width];
        result.column = new int[span][result.depth][result.width];
        for (char[][] layer : result.cells) {
            for (char[] row : layer) {
                Arrays.fill(row, Blueprint.ANY);
            }
        }
        for (Grid grid : baseGrids) {
            boolean shared = grid.indices().size() > 1;
            for (int z = 0; z < result.depth; z++) {
                Row row = grid.rows().get(z);
                List<Integer> nodeColumns = new ArrayList<>();
                for (int x = 0; x < result.width; x++) {
                    char glyph = row.cells().charAt(x);
                    if (glyph == ' ') {
                        glyph = Blueprint.AIR;
                    }
                    if (glyph == Blueprint.NODE_AIR && !shared) {
                        nodeColumns.add(x);
                    }
                    for (int index : grid.indices()) {
                        result.cells[index - boxMin][z][x] = glyph;
                        result.line[index - boxMin][z][x] = row.line();
                        result.column[index - boxMin][z][x] = row.column() + x;
                    }
                }
                placeIds(row, nodeColumns, grid.indices().get(0), z, true, result, shared);
            }
        }
        for (String key : groups.declared.keySet()) {
            result.overlays.put(key, overlay(key, blockGrids, result));
        }
        for (Grid grid : src.grids()) {
            if (!grid.nodes() || grid.indices().size() != 1) {
                continue;
            }
            int layer = grid.indices().get(0);
            if (layer < boxMin || layer > boxMax) {
                out.error("node_layer_range", grid.line(), 1, "node layer " + layer + " is outside the structure, "
                        + "which spans layers " + boxMin + " to " + boxMax);
                continue;
            }
            for (int z = 0; z < result.depth; z++) {
                Row row = grid.rows().get(z);
                List<Integer> nodeColumns = new ArrayList<>();
                for (int x = 0; x < result.width; x++) {
                    char glyph = row.cells().charAt(x);
                    if (glyph != ' ' && glyph != Blueprint.AIR) {
                        nodeColumns.add(x);
                    }
                }
                placeIds(row, nodeColumns, layer, z, false, result, false);
            }
        }
        return result;
    }

    /** A variant's grids as one overlay on the box: its glyph where it draws one, unchanged at {@code ?}. */
    private static Overlay overlay(String key, List<Grid> grids, GridResult box) {
        int span = box.cells.length;
        char[][][] cells = new char[span][box.depth][box.width];
        int[][][] line = new int[span][box.depth][box.width];
        int[][][] column = new int[span][box.depth][box.width];
        Set<Integer> layers = new TreeSet<>();
        for (Grid grid : grids) {
            if (!key.equals(grid.variant())) {
                continue;
            }
            for (int index : grid.indices()) {
                layers.add(index);
                for (int z = 0; z < box.depth; z++) {
                    Row row = grid.rows().get(z);
                    for (int x = 0; x < box.width; x++) {
                        char glyph = row.cells().charAt(x);
                        if (glyph == Blueprint.ANY) {
                            continue;
                        }
                        cells[index - box.minLayer][z][x] = glyph == ' ' ? Blueprint.AIR : glyph;
                        line[index - box.minLayer][z][x] = row.line();
                        column[index - box.minLayer][z][x] = row.column() + x;
                    }
                }
            }
        }
        return new Overlay(key, cells, line, column, Variants.sorted(layers));
    }

    /**
     * Every cell's glyph declared, no {@code @} in a shared grid, and no node in a variant's grid —
     * whatever the grid's shape.
     */
    private void glyphs(Grid grid) {
        boolean shared = grid.indices().size() > 1;
        boolean variant = grid.variant() != null;
        boolean nodeReported = false;
        for (int z = 0; z < grid.rows().size(); z++) {
            Row row = grid.rows().get(z);
            if (variant && !row.ids().isEmpty() && !nodeReported) {
                out.error("variant_nodes", row.line(), row.column(), "node ids belong to the base; a variant "
                        + "carries no nodes yet");
                nodeReported = true;
            }
            for (int x = 0; x < row.cells().length(); x++) {
                char glyph = row.cells().charAt(x);
                Cell cell = new Cell(grid.indices().isEmpty() ? 0 : grid.indices().get(0), x, z);
                if (glyph == Blueprint.NODE_AIR && variant && !nodeReported) {
                    out.cellError("variant_nodes", row.line(), row.column() + x, cell, "'@' marks a node, and a "
                            + "variant carries no nodes yet");
                    nodeReported = true;
                } else if (glyph == Blueprint.NODE_AIR && shared && !variant && !nodeReported) {
                    out.cellError("node_in_shared_layer", row.line(), row.column() + x, cell, "'@' in a grid drawn "
                            + "over several layers would mint each node id again; give this layer its own header");
                    nodeReported = true;
                } else if (glyph != ' ' && glyph != Blueprint.AIR && glyph != Blueprint.ANY
                        && glyph != Blueprint.TERRAIN && glyph != Blueprint.NODE_AIR && !legend.containsKey(glyph)
                        && !legendDeclares(glyph)) {
                    // A glyph declared on a broken line is not repeated here; that line says why.
                    out.cellError("unknown_glyph", row.line(), row.column() + x, cell, "glyph '" + glyph
                            + "' is not in the legend");
                }
            }
        }
    }

    private boolean legendDeclares(char glyph) {
        return src.brokenGlyphs().contains(glyph) || src.legend().stream().anyMatch(entry -> entry.glyph() == glyph);
    }

    private boolean shape(Grid grid, int width, int depth) {
        boolean fine = true;
        String what = (grid.nodes() ? "node layer " : "layer ") + grid.indices().stream().map(String::valueOf)
                .collect(Collectors.joining(" ")) + (grid.variant() == null ? "" : " " + grid.variant());
        if (grid.rows().size() != depth) {
            out.error("grid_depth", grid.line(), 1, what + ": " + grid.rows().size() + " rows, expected " + depth
                    + " — every grid has as many rows as the first");
            fine = false;
        }
        for (int z = 0; z < grid.rows().size(); z++) {
            Row row = grid.rows().get(z);
            if (row.cells().length() != width) {
                out.error("row_width", row.line(), row.column(), what + ": row " + z + " has " + row.cells().length()
                        + " cells, expected " + width + (row.cells().length() < width
                        ? " — rows are never padded; draw trailing air as '.'" : ""));
                fine = false;
            }
        }
        return fine;
    }

    private void placeIds(Row row, List<Integer> columns, int layer, int z, boolean inline, GridResult result,
                          boolean shared) {
        if (shared && row.ids().isEmpty()) {
            return;
        }
        if (columns.size() != row.ids().size()) {
            out.error("row_ids", row.line(), row.column(), "this row has " + columns.size() + " node"
                    + (columns.size() == 1 ? "" : "s") + " and " + row.ids().size() + " id"
                    + (row.ids().size() == 1 ? "" : "s") + " after '<'; they pair up left to right");
            return;
        }
        for (int i = 0; i < columns.size(); i++) {
            String nodeId = row.ids().get(i);
            if (!nodeId.matches("[A-Za-z0-9_\\-]+")) {
                out.error("node_id", row.line(), row.column(), "'" + nodeId + "' is not a node id");
                continue;
            }
            result.placed.add(new PlacedNode(nodeId, layer, columns.get(i), z, inline, row.line(),
                    row.column() + columns.get(i)));
        }
    }

    private void unusedGlyphs(GridResult result) {
        Set<Character> drawn = new HashSet<>();
        List<char[][][]> grids = new ArrayList<>();
        grids.add(result.cells);
        result.overlays.values().forEach(overlay -> grids.add(overlay.cells()));
        for (char[][][] cells : grids) {
            for (char[][] layer : cells) {
                for (char[] row : layer) {
                    for (char glyph : row) {
                        drawn.add(glyph);
                    }
                }
            }
        }
        for (EntryInfo entry : legend.values()) {
            if (!drawn.contains(entry.glyph())) {
                out.report("unused_glyph", entry.line(), 1, "'" + entry.glyph() + "' is never drawn");
            }
        }
    }

    // ── nodes and paths ─────────────────────────────────────────────────────────────────────

    private Map<String, Node> nodes(GridResult grids) {
        Map<String, NodeDecl> declared = new LinkedHashMap<>();
        for (NodeDecl decl : src.nodes()) {
            if (declared.putIfAbsent(decl.id(), decl) != null) {
                out.error("node_twice", decl.line(), decl.column(), "node '" + decl.id() + "' is declared twice");
                continue;
            }
            if (!NodeTypes.isKnown(decl.type())) {
                out.error("unknown_node_type", decl.line(), decl.column(), "no node type '" + decl.type() + "'"
                        + Suggest.hint(decl.type(), NodeTypes.known()) + "; bp 1 knows "
                        + String.join(", ", NodeTypes.known()));
            }
        }
        Map<String, Node> nodes = new LinkedHashMap<>();
        for (PlacedNode placed : grids.placed) {
            NodeDecl decl = declared.get(placed.id());
            if (nodes.containsKey(placed.id())) {
                out.error("node_twice", placed.line(), placed.column(), "node '" + placed.id() + "' is placed twice; "
                        + "ids are unique per file");
                continue;
            }
            if (decl == null) {
                out.error("node_undeclared", placed.line(), placed.column(), "node '" + placed.id() + "' has no line "
                        + "in 'nodes'" + Suggest.hint(placed.id(), declared.keySet()));
                continue;
            }
            nodes.put(placed.id(), new Node(placed.id(), decl.type(), placed.layer(), placed.x(), placed.z(),
                    placed.inline(), placed.line()));
        }
        for (NodeDecl decl : declared.values()) {
            if (!nodes.containsKey(decl.id()) && grids.placed.stream().noneMatch(p -> p.id().equals(decl.id()))) {
                out.error("node_unplaced", decl.line(), decl.column(), "node '" + decl.id() + "' is declared but "
                        + "never placed in a grid");
            }
        }
        return nodes;
    }

    private List<Edge> paths(Map<String, Node> nodes) {
        List<Edge> edges = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (PathChain chain : src.paths()) {
            boolean fine = true;
            for (int i = 0; i < chain.ids().size(); i++) {
                String nodeId = chain.ids().get(i);
                Node node = nodes.get(nodeId);
                if (node == null) {
                    if (src.nodes().stream().noneMatch(decl -> decl.id().equals(nodeId))) {
                        out.error("path_unknown", chain.line(), chain.columns().get(i), "no node '" + nodeId + "'"
                                + Suggest.hint(nodeId, nodes.keySet()));
                    }
                    fine = false;
                } else if (!node.type().equals(NodeTypes.PATH_NODE)) {
                    out.error("path_type", chain.line(), chain.columns().get(i), "'" + nodeId + "' is a "
                            + node.type() + "; a path joins path_node nodes");
                    fine = false;
                }
            }
            if (!fine) {
                continue;
            }
            for (int i = 0; i + 1 < chain.ids().size(); i++) {
                String from = chain.ids().get(i);
                String to = chain.ids().get(i + 1);
                if (from.equals(to)) {
                    out.error("path_loop", chain.line(), chain.columns().get(i + 1), "'" + from + " > " + to
                            + "' goes nowhere");
                    continue;
                }
                if (!seen.add(from + ">" + to)) {
                    out.report("path_twice", chain.line(), chain.columns().get(i + 1), "'" + from + " > " + to
                            + "' is already a path");
                    continue;
                }
                edges.add(new Edge(from, to, chain.line()));
            }
        }
        return edges;
    }

    // ── fixtures ────────────────────────────────────────────────────────────────────────────

    /** What kind of multi-cell fixture an entry is, and which part it draws. */
    private record Part(Fixture fixture, boolean main, @Nullable Facing facing, Set<String> blocks,
                        Map<String, String> props) {
    }

    /** A bed's head and foot, or a door's or tall plant's two halves. */
    enum Fixture {
        BED("part", "head", "foot"),
        TALL("half", "lower", "upper");

        /** The property that names the part, and its value on the main part and on the other. */
        final String key;
        final String main;
        final String other;

        Fixture(String key, String main, String other) {
            this.key = key;
            this.main = main;
            this.other = other;
        }
    }

    /** Null when the entry is no fixture; an error, and null, when its outcomes disagree. */
    private @Nullable Part part(EntryInfo entry) {
        Fixture kind = null;
        Boolean main = null;
        Facing facing = null;
        Set<String> blocks = new TreeSet<>();
        Map<String, String> props = new TreeMap<>();
        for (Outcome outcome : entry.outcomes()) {
            BlockInfo info = dict.block(outcome.block()).orElse(null);
            if (info == null) {
                return null;
            }
            Fixture here = fixtureOf(info);
            if (kind != null && here != kind) {
                out.error("fixture_mixed", entry.line(), 1, "'" + entry.glyph() + "' mixes a fixture with blocks that "
                        + "are not one; each part of a fixture is its own entry");
                return null;
            }
            kind = here;
            if (here == null) {
                continue;
            }
            String partKey = here.key;
            // A part left unsaid is the main part, whatever vanilla's default (a bed's is the foot).
            boolean isMain = here.main.equals(outcome.props().getOrDefault(partKey, here.main));
            if (main != null && main != isMain) {
                out.error("fixture_mixed", entry.line(), 1, "'" + entry.glyph() + "' is sometimes the main part of "
                        + "its fixture and sometimes not");
                return null;
            }
            main = isMain;
            if (here == Fixture.BED) {
                String word = outcome.props().getOrDefault("facing", info.defaults().getOrDefault("facing", "north"));
                facing = Facing.of(word);
            }
            blocks.add(outcome.block());
            outcome.props().forEach((key, value) -> {
                if (!key.equals(partKey)) {
                    props.put(key, value);
                }
            });
        }
        if (kind == null || main == null) {
            return null;
        }
        return new Part(kind, main, facing, blocks, props);
    }

    static @Nullable Fixture fixtureOf(BlockInfo info) {
        List<String> part = info.properties().get("part");
        if (part != null && part.containsAll(List.of("head", "foot")) && info.properties().containsKey("facing")) {
            return Fixture.BED;
        }
        List<String> half = info.properties().get("half");
        if (half != null && half.size() == 2 && half.containsAll(List.of("lower", "upper"))) {
            return Fixture.TALL;
        }
        return null;
    }

    private Map<Character, Part> fixtureParts() {
        Map<Character, Part> parts = new HashMap<>();
        for (EntryInfo entry : legend.values()) {
            Part part = part(entry);
            if (part != null) {
                parts.put(entry.glyph(), part);
            }
        }
        return parts;
    }

    /**
     * The local rule: a main part's partner cell holds its matching other part or air, and every
     * other part lands on some main part's partner cell. Run on each selection's cells.
     */
    private static void fixtures(Map<Character, Part> parts, Variants.Composed composed, GridResult box,
                                 Diagnostics out) {
        if (parts.isEmpty()) {
            return;
        }
        char[][][] cells = composed.cells();
        int layers = cells.length;
        Set<Cell> claimedPartners = new HashSet<>();
        for (int li = 0; li < layers; li++) {
            for (int z = 0; z < box.depth; z++) {
                for (int x = 0; x < box.width; x++) {
                    Part part = parts.get(cells[li][z][x]);
                    if (part == null || !part.main()) {
                        continue;
                    }
                    int layer = li + box.minLayer;
                    Cell here = new Cell(layer, x, z);
                    int[] origin = {composed.line()[li][z][x], composed.column()[li][z][x]};
                    Cell partner = partnerOf(part, here);
                    String partName = part.fixture() == Fixture.BED ? "foot" : "upper half";
                    if (partner.layer() - box.minLayer >= layers || partner.x() < 0 || partner.x() >= box.width
                            || partner.z() < 0 || partner.z() >= box.depth) {
                        out.cellError("fixture_outside", origin[0], origin[1], here, "its " + partName + " falls "
                                + "outside the structure");
                        continue;
                    }
                    char there = cells[partner.layer() - box.minLayer][partner.z()][partner.x()];
                    Part other = parts.get(there);
                    if (there == Blueprint.AIR || there == Blueprint.ANY || there == Blueprint.NODE_AIR) {
                        claimedPartners.add(partner);
                    } else if (other != null && !other.main() && other.fixture() == part.fixture()
                            && other.blocks().equals(part.blocks()) && other.props().equals(part.props())) {
                        claimedPartners.add(partner);
                    } else {
                        out.cellError("fixture_collision", origin[0], origin[1], here, "its " + partName + " lands "
                                + "on '" + there + "' at " + partner + "; that cell must be air or the matching part");
                    }
                }
            }
        }
        for (int li = 0; li < layers; li++) {
            for (int z = 0; z < box.depth; z++) {
                for (int x = 0; x < box.width; x++) {
                    char glyph = cells[li][z][x];
                    Part part = parts.get(glyph);
                    Cell here = new Cell(li + box.minLayer, x, z);
                    if (part != null && !part.main() && !claimedPartners.contains(here)) {
                        out.cellError("fixture_lonely", composed.line()[li][z][x], composed.column()[li][z][x], here,
                                "'" + glyph + "' is the " + (part.fixture() == Fixture.BED ? "foot of a bed"
                                        : "upper half of a fixture") + " with no main part where it would infer "
                                        + "this cell");
                    }
                }
            }
        }
    }

    private static Cell partnerOf(Part part, Cell main) {
        return partnerOf(part.fixture(), part.facing(), main);
    }

    /** Where a fixture's other part goes, from its main part's cell. */
    static Cell partnerOf(Fixture fixture, @Nullable Facing facing, Cell main) {
        if (fixture == Fixture.TALL) {
            return new Cell(main.layer() + 1, main.x(), main.z());
        }
        if (facing == null) {
            facing = Facing.NORTH;
        }
        // A bed's facing points from foot to head, so the foot is one step behind the head.
        return new Cell(main.layer(), main.x() - facing.dx, main.z() - facing.dz);
    }

    // ── vocabulary ──────────────────────────────────────────────────────────────────────────

    private Set<String> briefBlocks() {
        return dict.blocks().keySet().stream().map(Ids::brief).collect(Collectors.toSet());
    }

    private Set<String> briefMaterials() {
        return dict.materials().keySet().stream().map(Ids::brief).collect(Collectors.toSet());
    }
}
