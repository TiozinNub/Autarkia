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
