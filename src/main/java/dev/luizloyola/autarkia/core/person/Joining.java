package dev.luizloyola.autarkia.core.person;

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

    /** Who would move, read from either side's point of view. */
    public enum Mover {
        SELF, OTHER, EITHER, NEITHER
    }

    /** The two ways to raise it. */
    public enum Ask {
        /** "Can I join you?" — the asker moves. */
        JOIN,
        /** "Want to join us?" — the invited side moves. */
        INVITE
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
