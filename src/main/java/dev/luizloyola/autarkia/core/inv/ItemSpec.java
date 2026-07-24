package dev.luizloyola.autarkia.core.inv;

import java.util.function.Predicate;

/**
 * A named CLASS of items — a family matched by predicate over the core inventory's id strings. One
 * spec object serves everyone who must agree (the board's stock predicate, an {@code ObtainItem}'s
 * satisfied-check, the drop filters), so there is no drift.
 *
 * <p>String-level vanilla knowledge ({@code *_log}/{@code *_stem}), provisional until a compat tag
 * lens ({@code ItemTags.LOGS}) replaces the predicate; the record shape stays.
 */
public record ItemSpec(String name, Predicate<String> matcher) {
    /** Wood in log form — every overworld {@code *_log} plus the nether {@code *_stem}s. */
    public static final ItemSpec LOGS =
            new ItemSpec("logs", id -> id.endsWith("_log") || id.endsWith("_stem"));

    public boolean matches(String itemId) {
        return matcher.test(itemId);
    }
}
