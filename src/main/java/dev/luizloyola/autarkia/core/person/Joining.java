package dev.luizloyola.autarkia.core.person;

import dev.luizloyola.anima.core.social.GroupUp.Ask;
import java.util.Optional;

/**
 * Which of two parties moves into the other when they group up (decisions: Luiz, 2026-10-21,
 * group-up spec, decisions 1 and 5). Only a homeless side moves; two settled parties never merge.
 * Between two homeless sides a led party stays, then the larger, and at equal size what was said
 * decides: the asker moves for "Can I join you?", the invited side for "Want to join us?".
 */
public final class Joining {

    /** One side as the rule sees it. */
    public record Side(boolean homeless, boolean led, int size) {
    }

    /** What a body's move takes with it. */
    public enum Going {
        /** Its whole party: it leads it, the party has no leader, or it is a party of one. */
        PARTY,
        /** Only itself, its party left behind, and past a higher bar ({@link #ALONE_MARGIN}). */
        ALONE,
        /** Nothing: its leader is a player, who has to be asked first (not built yet). */
        NOBODY
    }

    /**
     * How far past its party's own threshold a member's willingness to join must reach before it
     * leaves its party for another. A starting value.
     */
    public static final double ALONE_MARGIN = 20.0;

    /** Who would move, read from either side's point of view. */
    public enum Mover {
        SELF, OTHER, EITHER, NEITHER
    }

    private Joining() {
    }

    /**
     * Whether the scout of a party's HOME search becomes its leader. Only with company: a loner
     * searching alone leads nobody, so a player who forms a party with one leads it (decision 13;
     * found in flight 2026-10-21, when every loner had led itself from the first step).
     */
    public static boolean scoutLeads(int members, boolean led) {
        return !led && members >= 2;
    }

    /**
     * What a move by a body in a party of {@code members} takes (decision 7, Luiz 2026-11-02): only
     * a party's leader moves the party. Under a settler leader a member goes alone; under a player
     * leader it goes nowhere until it can ask them. A party with no leader yet is moved by whichever
     * member speaks, as before.
     */
    public static Going going(int members, boolean led, boolean isLeader, boolean leaderIsPlayer) {
        if (members < 2 || !led || isLeader) {
            return Going.PARTY;
        }
        return leaderIsPlayer ? Going.NOBODY : Going.ALONE;
    }

    /** Who moves between {@code self} and {@code other}, before anything is said. */
    public static Mover between(Side self, Side other) {
        if (!self.homeless() && !other.homeless()) {
            return Mover.NEITHER;
        }
        if (self.homeless() != other.homeless()) {
            return self.homeless() ? Mover.SELF : Mover.OTHER;
        }
        if (self.led() != other.led()) {
            return self.led() ? Mover.OTHER : Mover.SELF;
        }
        if (self.size() != other.size()) {
            return self.size() < other.size() ? Mover.SELF : Mover.OTHER;
        }
        return Mover.EITHER;
    }

    /**
     * The ask {@code self} could make of {@code other}: to join when self would move, to invite when
     * the other would, either at a tie (joining, which is what a homeless body wants). Empty when
     * neither may move.
     */
    public static Optional<Ask> askFor(Side self, Side other) {
        return switch (between(self, other)) {
            case SELF, EITHER -> Optional.of(Ask.JOIN);
            case OTHER -> Optional.of(Ask.INVITE);
            case NEITHER -> Optional.empty();
        };
    }

    /**
     * Whether {@code ask}, made by {@code asker} of {@code asked}, still means what it says: the
     * side its words move is a side the rule lets move. Read again on the accepting line, since
     * either party may have settled or grown since the ask.
     */
    public static boolean holds(Side asker, Side asked, Ask ask) {
        Mover mover = between(asker, asked);
        return switch (ask) {
            case JOIN -> mover == Mover.SELF || mover == Mover.EITHER;
            case INVITE -> mover == Mover.OTHER || mover == Mover.EITHER;
        };
    }
}
