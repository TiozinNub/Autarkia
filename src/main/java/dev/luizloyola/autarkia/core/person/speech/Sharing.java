package dev.luizloyola.autarkia.core.person.speech;

import dev.luizloyola.anima.core.social.speech.Handover;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * How much food a person gives somebody who asks (decision: Luiz, 2026-10-02;
 * 2026-10-02-food-and-replies-design.md). Party first:
 *
 * <ul>
 *   <li><b>A colleague</b> gets half the food points carried.</li>
 *   <li><b>A stranger</b> gets something only from a surplus — what is carried beyond
 *       {@link #RESERVE}, a full bar — and at most the smaller of the share fraction of the food
 *       carried and {@link #STRANGER_CAP}.</li>
 * </ul>
 *
 * <p>Both are counted the same way: items are added while the total is under the limit, so the
 * last one may take it over — six steaks against a limit of 12 give two (16) — and anybody who
 * gives at all gives at least one item, whatever its size. The kind carried most of goes first.
 */
public final class Sharing {

    /** What a body keeps for itself before anything carried is a surplus: a full bar. */
    public static final int RESERVE = 20;
    /** The most food points a stranger is given. */
    public static final int STRANGER_CAP = 20;

    /** One kind of ready food carried: how many, and what each is worth. */
    public record Food(String id, int count, int nutrition) {
    }

    private Sharing() {
    }

    /** What to hold out; empty when there is nothing to spare. */
    public static List<Handover.Item> share(List<Food> carried, boolean colleague,
            double strangerFraction) {
        int total = 0;
        for (Food food : carried) {
            total += food.count() * food.nutrition();
        }
        double limit;
        if (colleague) {
            limit = total / 2.0;
        } else if (total <= RESERVE) {
            return List.of();
        } else {
            limit = Math.min(strangerFraction * total, STRANGER_CAP);
        }
        if (limit <= 0) {
            return List.of();
        }
        List<Food> byCount = new ArrayList<>(carried);
        byCount.sort(Comparator.comparingInt(Food::count).reversed());
        Map<String, Integer> given = new LinkedHashMap<>();
        int points = 0;
        for (Food food : byCount) {
            for (int i = 0; i < food.count() && points < limit; i++) {
                given.merge(food.id(), 1, Integer::sum);
                points += food.nutrition();
            }
        }
        List<Handover.Item> out = new ArrayList<>();
        given.forEach((id, count) -> out.add(new Handover.Item(id, count)));
        return List.copyOf(out);
    }
}
