package dev.luizloyola.autarkia.core.bp;

import java.util.Collection;
import java.util.Optional;

/** "Did you mean": the nearest known word by edit distance, when one is near enough to be a typo. */
public final class Suggest {

    private Suggest() {
    }

    /**
     * The closest candidate within a third of the word's length (at least one edit, at most three);
     * ties go to the alphabetically first, so the answer never depends on iteration order.
     */
    public static Optional<String> closest(String word, Collection<String> candidates) {
        int limit = Math.max(1, Math.min(3, word.length() / 3));
        String best = null;
        int bestDistance = Integer.MAX_VALUE;
        for (String candidate : candidates) {
            if (Math.abs(candidate.length() - word.length()) > limit) {
                continue;
            }
            int distance = distance(word, candidate, limit);
            if (distance <= limit && (distance < bestDistance
                    || distance == bestDistance && best != null && candidate.compareTo(best) < 0)) {
                best = candidate;
                bestDistance = distance;
            }
        }
        return Optional.ofNullable(best);
    }

    /** {@code " — did you mean 'x'?"}, or nothing. */
    public static String hint(String word, Collection<String> candidates) {
        return closest(word, candidates).map(found -> " — did you mean '" + found + "'?").orElse("");
    }

    /** Levenshtein, abandoned once every cell of a row exceeds {@code limit}. */
    static int distance(String a, String b, int limit) {
        int[] previous = new int[b.length() + 1];
        int[] current = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) {
            previous[j] = j;
        }
        for (int i = 1; i <= a.length(); i++) {
            current[0] = i;
            int rowMin = current[0];
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                current[j] = Math.min(Math.min(current[j - 1] + 1, previous[j] + 1), previous[j - 1] + cost);
                rowMin = Math.min(rowMin, current[j]);
            }
            if (rowMin > limit) {
                return rowMin;
            }
            int[] swap = previous;
            previous = current;
            current = swap;
        }
        return previous[b.length()];
    }
}
