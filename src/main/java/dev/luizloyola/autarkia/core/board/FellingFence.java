package dev.luizloyola.autarkia.core.board;

import dev.luizloyola.anima.core.brain.sense.Pos;

/**
 * Cells no felling may take: the parties' buildings, once their walls start going up. The ledger
 * never admits an anchor here and the axe never swings at a cell here.
 *
 * <p>A house without its roof is all log columns with nothing on top, which is exactly what
 * {@link Felling#residue} takes for stubs: an area felling chopped 22 logs of a frame while it was
 * being built (2026-10-21).
 */
public final class FellingFence {

    @FunctionalInterface
    public interface Rule {
        boolean keepsOff(Pos at);
    }

    private static volatile Rule rule = at -> false;

    private FellingFence() {
    }

    /** Installed once by the mod, which holds the buildings. */
    public static void keepOffBy(Rule next) {
        rule = next;
    }

    public static boolean keepsOff(Pos at) {
        return rule.keepsOff(at);
    }
}
