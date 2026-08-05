package dev.luizloyola.autarkia.core.board;

import dev.luizloyola.anima.core.inv.ItemSpec;

/**
 * The item kinds Autarkia's settlers care about holding — here rather than in Anima because which
 * items matter is a question about the world being modelled, not about having a mind.
 *
 * <p>These constants are the keys {@code Producers} is registered against, so everything that wants
 * logs must want <em>this</em> LOGS.
 */
public final class Stock {

    /** Wood in log form — every overworld {@code *_log} plus the nether {@code *_stem}s.
     *  String-level vanilla knowledge, the same convention the chop's sapling map uses;
     *  provisional until a compat tag lens ({@code ItemTags.LOGS}) replaces the predicate. */
    public static final ItemSpec LOGS =
            ItemSpec.register(
                    new ItemSpec("logs", id -> id.endsWith("_log") || id.endsWith("_stem")));

    private Stock() {
    }
}
