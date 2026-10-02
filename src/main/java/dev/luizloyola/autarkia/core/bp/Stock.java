package dev.luizloyola.autarkia.core.bp;

import dev.luizloyola.autarkia.core.bp.Chooser.Choice;
import dev.luizloyola.autarkia.core.bp.Dictionary.Material;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * What a party has of each leaf material, as a {@link Chooser#stocked} chooser weighs it: what it
 * holds (its stores, its members' packs) and what it has seen growing (the trees it remembers).
 * Counted in items of any form, so jungle planks count for jungle as its logs do. The mod side
 * gathers the counts; nothing here reads a world.
 */
public final class Stock {

    public static final Stock NONE = new Stock(Map.of(), Map.of(), Map.of());

    /** A block or item to the leaf material it is a form of. */
    private final Map<String, String> materialOf;
    private final Map<String, Integer> held;
    private final Map<String, Integer> growing;

    private Stock(Map<String, String> materialOf, Map<String, Integer> held, Map<String, Integer> growing) {
        this.materialOf = materialOf;
        this.held = held;
        this.growing = growing;
    }

    /**
     * @param heldItems    item id to how many the party holds
     * @param growingItems item id to how many it has seen growing — a tree's logs
     */
    public static Stock of(Dictionary dict, Map<String, Integer> heldItems, Map<String, Integer> growingItems) {
        Map<String, String> materialOf = new HashMap<>();
        for (Material material : dict.materials().values()) {
            for (String block : material.forms().values()) {
                materialOf.putIfAbsent(block, material.id());
                dict.block(block).map(Dictionary.BlockInfo::item).filter(item -> !item.isEmpty())
                        .ifPresent(item -> materialOf.putIfAbsent(item, material.id()));
            }
        }
        return new Stock(materialOf, tally(materialOf, heldItems), tally(materialOf, growingItems));
    }

    private static Map<String, Integer> tally(Map<String, String> materialOf, Map<String, Integer> items) {
        Map<String, Integer> out = new HashMap<>();
        items.forEach((item, count) -> {
            String material = materialOf.get(item);
            if (material != null && count > 0) {
                out.merge(material, count, Integer::sum);
            }
        });
        return out;
    }

    public int held(String material) {
        return held.getOrDefault(material, 0);
    }

    public int growing(String material) {
        return growing.getOrDefault(material, 0);
    }

    /** Whether the party holds any of it or knows where it grows. */
    public boolean known(String material) {
        return held(material) > 0 || growing(material) > 0;
    }

    /**
     * The best of {@code choices} by stock, as indices — several when they tie — or empty when the
     * stock knows none of them. Held enough for the choice's need comes first, the most held; then
     * held and growing together, the most of both: two oak logs from a bush lose to a jungle.
     */
    public List<Integer> best(List<Choice> choices) {
        List<Integer> enough = new ArrayList<>();
        List<Integer> known = new ArrayList<>();
        int mostHeld = 0;
        int mostKnown = 0;
        for (int i = 0; i < choices.size(); i++) {
            Set<String> materials = materials(choices.get(i).blocks());
            int has = 0;
            int grows = 0;
            for (String material : materials) {
                has += held(material);
                grows += growing(material);
            }
            if (has > 0 && has >= choices.get(i).need()) {
                mostHeld = keep(enough, i, has, mostHeld);
            }
            if (has + grows > 0) {
                mostKnown = keep(known, i, has + grows, mostKnown);
            }
        }
        return !enough.isEmpty() ? enough : known;
    }

    private static int keep(List<Integer> best, int i, int score, int top) {
        if (score > top) {
            best.clear();
        }
        if (score >= top) {
            best.add(i);
            return score;
        }
        return top;
    }

    /** The leaf materials these blocks are forms of. */
    public Set<String> materials(Set<String> blocks) {
        Set<String> out = new LinkedHashSet<>();
        for (String block : blocks) {
            String material = materialOf.get(block);
            if (material != null) {
                out.add(material);
            }
        }
        return out;
    }
}
