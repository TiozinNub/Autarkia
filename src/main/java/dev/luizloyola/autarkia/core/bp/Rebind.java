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
     * A quick building's palette slot whose block its pieces came back short of, bound again to a
     * way of the slot the party holds enough of for every cell that reads it. A first-night hut
     * bound to oak planks gave up its floor 336 times with no logs to be had while its settler
     * carried 57 dirt (2026-10-21). As in {@link #of}, only while none of the slot's block stands; a
     * material slot (the door's wood) stays as bound.
     *
     * @param starved item ids the build's pieces came back short of
     * @param placed  item ids of the steps that stand
     */
    public static Optional<Rebound> held(Blueprint bp, Dictionary dict, Support support, Map<Integer, String> bindings,
                                         Map<String, String> variants, Set<String> starved, Set<String> placed,
                                         Stock stock) {
        for (SlotInfo slot : bp.slots()) {
            String bound = bindings.get(slot.number());
            if (slot.kind() != SlotKind.PALETTE || slot.binding() != Binding.OPTION || bound == null
                    || bound.startsWith("$")) {
                continue;
            }
            String item = itemOf(dict, Ids.qualify(bound));
            if (!starved.contains(item) || placed.contains(item)) {
                continue;
            }
            Map<Integer, String> pins = new LinkedHashMap<>(bindings);
            pins.remove(slot.number());
            // Nothing is gettable but what is held in full, and the first way otherwise: the bound one.
            BuildPlan plan = Planner.plan(bp, dict, support, pins, variants,
                    Chooser.gettable(stock, blocks -> false, (what, choices) -> 0), new java.util.Random(0),
                    new Diagnostics());
            String to = plan == null ? null : plan.bindings().get(slot.number());
            if (to == null || to.equals(bound) || to.startsWith("$")) {
                continue;
            }
            String toBlock = Ids.qualify(to);
            String toItem = itemOf(dict, toBlock);
            int need = plan.bill().stream().filter(line -> line.items().contains(toItem))
                    .mapToInt(BuildPlan.BillLine::count).sum();
            // The chooser's fallback is the slot's first way, which need not be held at all.
            if (!stock.enough(List.of(new Choice(to, Set.of(toBlock), 0, need))).isEmpty()) {
                return Optional.of(new Rebound(slot.number(), bound, to, Set.of(item), plan.bindings()));
            }
        }
        return Optional.empty();
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
