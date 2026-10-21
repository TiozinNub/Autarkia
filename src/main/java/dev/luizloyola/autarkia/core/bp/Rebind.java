package dev.luizloyola.autarkia.core.bp;

import dev.luizloyola.autarkia.core.bp.Blueprint.SlotInfo;
import dev.luizloyola.autarkia.core.bp.Blueprint.SlotKind;
import dev.luizloyola.autarkia.core.bp.Chooser.Choice;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/**
 * A material slot bound to what nobody can get, bound again to what the party has or can get.
 *
 * <p>Only while none of the slot's blocks stand: an {@code option} is one choice for the whole
 * structure, so re-binding the rest of a started slot would mix two woods in one wall. A started
 * slot's material was got once, and the build waits for more of it.
 */
public final class Rebind {

    private Rebind() {
    }

    /**
     * @param from         the material it was bound to
     * @param to           the one it is bound to now
     * @param unobtainable the slot's items nobody could get
     * @param bindings     every binding, this slot's changed
     */
    public record Rebound(int slot, String from, String to, Set<String> unobtainable, Map<Integer, String> bindings) {
        public Rebound {
            unobtainable = Set.copyOf(unobtainable);
            bindings = Map.copyOf(bindings);
        }
    }

    /**
     * The first slot to bind again, or empty when none should be: none of its items unobtainable,
     * one of them already placed, the party knowing some of its material after all, or no other
     * material the slot allows that the party has or knows of.
     *
     * @param unobtainable item ids the build's pieces were passed over for
     * @param placed       item ids of the steps that stand
     * @param left         item id to how many the steps still to place take
     */
    public static Optional<Rebound> of(Blueprint bp, Dictionary dict, Map<Integer, String> bindings,
                                       Set<String> unobtainable, Set<String> placed, Map<String, Integer> left,
                                       Stock stock) {
        for (SlotInfo slot : bp.slots()) {
            String bound = bindings.get(slot.number());
            if (slot.kind() != SlotKind.MATERIAL || slot.binding() != Binding.OPTION || bound == null
                    || bound.startsWith("$")) {
                continue;
            }
            String material = Ids.qualify(bound);
            Set<String> items = items(dict, material, slot);
            Set<String> missing = new TreeSet<>(items);
            missing.retainAll(unobtainable);
            if (missing.isEmpty() || stock.known(material) || items.stream().anyMatch(placed::contains)) {
                continue;
            }
            int need = items.stream().mapToInt(item -> left.getOrDefault(item, 0)).sum();
            List<String> candidates = new ArrayList<>();
            List<Choice> choices = new ArrayList<>();
            for (String other : new TreeSet<>(slot.domain())) {
                Set<String> its = items(dict, other, slot);
                if (!other.equals(material) && its.stream().noneMatch(unobtainable::contains)) {
                    candidates.add(other);
                    choices.add(new Choice(Ids.brief(other), its, 0, need));
                }
            }
            List<Integer> best = stock.best(choices);
            if (best.isEmpty()) {
                continue;
            }
            String to = candidates.get(best.get(0));
            Map<Integer, String> next = new LinkedHashMap<>(bindings);
            next.put(slot.number(), Ids.brief(to));
            return Optional.of(new Rebound(slot.number(), Ids.brief(material), Ids.brief(to), missing, next));
        }
        return Optional.empty();
    }

    /**
     * A quick building's slots whose blocks its pieces came back short of, bound again to ways the
     * party holds enough of for the cells still to place. A first-night hut bound to oak planks gave
     * up its floor 336 times with no logs to be had while its settler carried 57 dirt (2026-10-21);
     * another, its floor down in oak with the local oaks felled, waited on its walls while the
     * stores held 213 logs of another wood.
     *
     * <p>A wood ({@code material}) slot is bound again even with some of it standing, and the
     * palette slots made of its forms follow it: a hut of two woods is a hut, and the standing
     * cells count as done ({@link #sameForm}). Not dying of a deadlock beats one wood (Luiz,
     * 2026-10-21). A palette slot on its own (planks to dirt) only while none of its block stands:
     * a wall of planks and dirt would have its planks broken out to match.
     *
     * @param starved item ids the build's pieces came back short of
     * @param placed  item ids of the steps that stand
     * @param left    item id to how many the steps still to place take
     */
    public static Optional<Rebound> held(Blueprint bp, Dictionary dict, Support support, Map<Integer, String> bindings,
                                         Map<String, String> variants, Set<String> starved, Set<String> placed,
                                         Map<String, Integer> left, Stock stock) {
        Map<Integer, Set<String>> woods = new LinkedHashMap<>();
        Map<Integer, Set<String>> palettes = new LinkedHashMap<>();
        for (SlotInfo slot : bp.slots()) {
            String bound = bindings.get(slot.number());
            if (slot.binding() != Binding.OPTION || bound == null || bound.startsWith("$")) {
                continue;
            }
            Set<String> items = itemsOf(dict, slot, bound);
            if (items.stream().noneMatch(starved::contains)) {
                continue;
            }
            if (slot.kind() == SlotKind.MATERIAL) {
                woods.put(slot.number(), items);
                for (SlotInfo palette : bp.slots()) {
                    String block = bindings.get(palette.number());
                    if (palette.kind() == SlotKind.PALETTE && block != null
                            && formOf(dict, Ids.qualify(bound), Ids.qualify(block)).isPresent()) {
                        woods.put(palette.number(), itemsOf(dict, palette, block));
                    }
                }
            } else if (items.stream().noneMatch(placed::contains)) {
                palettes.put(slot.number(), items);
            }
        }
        Map<Integer, Set<String>> both = new LinkedHashMap<>(palettes);
        both.putAll(woods);
        Optional<Rebound> rebound = woods.isEmpty() ? Optional.empty()
                : unpinned(bp, dict, support, bindings, variants, starved, left, stock, both);
        return rebound.isPresent() || palettes.isEmpty() ? rebound
                : unpinned(bp, dict, support, bindings, variants, starved, left, stock, palettes);
    }

    /** These slots planned again by what is held; empty unless each one changed is held enough of. */
    private static Optional<Rebound> unpinned(Blueprint bp, Dictionary dict, Support support,
                                              Map<Integer, String> bindings, Map<String, String> variants,
                                              Set<String> starved, Map<String, Integer> left, Stock stock,
                                              Map<Integer, Set<String>> unpinned) {
        Map<Integer, String> pins = new LinkedHashMap<>(bindings);
        pins.keySet().removeAll(unpinned.keySet());
        // Nothing is gettable but what is held in full, and the first way otherwise.
        BuildPlan plan = Planner.plan(bp, dict, support, pins, variants,
                Chooser.gettable(stock, blocks -> false, (what, choices) -> 0), new java.util.Random(0),
                new Diagnostics());
        if (plan == null) {
            return Optional.empty();
        }
        Rebound first = null;
        for (SlotInfo info : bp.slots()) {
            Set<String> items = unpinned.get(info.number());
            if (items == null) {
                continue;
            }
            String from = bindings.get(info.number());
            String to = plan.bindings().get(info.number());
            if (to == null || to.startsWith("$")) {
                return Optional.empty();
            }
            if (to.equals(from)) {
                continue;
            }
            int need = items.stream().mapToInt(item -> left.getOrDefault(item, 0)).sum();
            // The chooser's fallback is the slot's first way, which need not be held at all.
            if (stock.enough(List.of(new Choice(to, blocksOf(dict, info, to), 0, need))).isEmpty()) {
                return Optional.empty();
            }
            if (first == null) {
                Set<String> missing = new TreeSet<>(items);
                missing.retainAll(starved);
                first = new Rebound(info.number(), from, to, missing, plan.bindings());
            }
        }
        return Optional.ofNullable(first);
    }

    /**
     * Whether a block standing where a quick building's step wants another is the same form of
     * some material — oak planks for a spruce planks step, an oak door for a spruce door — and so
     * counts as done once the building is bound to another wood ({@link #held}).
     */
    public static boolean sameForm(Dictionary dict, String wanted, String standing) {
        if (wanted.equals(standing)) {
            return true;
        }
        for (Dictionary.Material material : dict.materials().values()) {
            for (Map.Entry<String, String> form : material.forms().entrySet()) {
                if (form.getValue().equals(wanted)) {
                    for (Dictionary.Material other : dict.materials().values()) {
                        if (standing.equals(other.forms().get(form.getKey()))) {
                            return true;
                        }
                    }
                }
            }
        }
        return false;
    }

    /** Which of {@code material}'s forms {@code block} is, if any. */
    private static Optional<String> formOf(Dictionary dict, String material, String block) {
        return dict.material(material).flatMap(found -> found.forms().entrySet().stream()
                .filter(form -> form.getValue().equals(block)).map(Map.Entry::getKey).findFirst());
    }

    /** The items a slot bound to {@code bound} places: a material's forms, or the one block. */
    private static Set<String> itemsOf(Dictionary dict, SlotInfo slot, String bound) {
        return slot.kind() == SlotKind.MATERIAL ? items(dict, Ids.qualify(bound), slot)
                : Set.of(itemOf(dict, Ids.qualify(bound)));
    }

    /** The blocks a slot bound to {@code bound} places. */
    private static Set<String> blocksOf(Dictionary dict, SlotInfo slot, String bound) {
        if (slot.kind() != SlotKind.MATERIAL) {
            return Set.of(Ids.qualify(bound));
        }
        Set<String> blocks = new TreeSet<>();
        for (String form : slot.forms()) {
            dict.lookup(Ids.qualify(bound), form).ifPresent(blocks::add);
        }
        return blocks;
    }

    private static String itemOf(Dictionary dict, String block) {
        return dict.block(block).map(Dictionary.BlockInfo::item).filter(id -> !id.isEmpty()).orElse(block);
    }

    /** The items the slot's forms of this material are placed with. */
    private static Set<String> items(Dictionary dict, String material, SlotInfo slot) {
        Set<String> items = new TreeSet<>();
        for (String form : slot.forms()) {
            dict.lookup(material, form).ifPresent(block -> items.add(dict.block(block)
                    .map(Dictionary.BlockInfo::item).filter(item -> !item.isEmpty()).orElse(block)));
        }
        return items;
    }
}
