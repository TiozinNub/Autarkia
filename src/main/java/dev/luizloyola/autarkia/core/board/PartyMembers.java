package dev.luizloyola.autarkia.core.board;

import dev.luizloyola.anima.core.agent.AgentId;
import dev.luizloyola.anima.core.social.PartyId;
import java.util.List;

/**
 * Who is in a party, as a project in {@code core/} can ask it — the roster seam, installed once at
 * bootstrap rather than looked up per beat.
 *
 * <p>The shape is Anima's {@code Places.asks(Parties)}: the roster lives in a {@code SavedData}
 * built before there is a server to ask, so the thing that needs it cannot construct it and is
 * handed it instead. Static rather than per-instance because a {@link PartyProject} is rebuilt from
 * a saved row by a codec that has nothing to thread through.
 *
 * <p><b>Empty until wired</b>, and that is the safe default: a party of nobody posts nothing, so an
 * un-installed roster costs an idle project rather than a crash or an errand offered to a ghost.
 */
public final class PartyMembers {

    /** How this asks who is in which party, so {@code core/} need not reach a {@code SavedData}. */
    public interface Roster {
        /** Everyone in this party, in roster order. Never creates a party. */
        List<AgentId> of(PartyId party);
    }

    private static final Roster NOBODY = party -> List.of();

    private static Roster roster = NOBODY;

    private PartyMembers() {
    }

    /** Installs the roster every party project asks about membership. */
    public static void asks(Roster roster) {
        PartyMembers.roster = roster;
    }

    /** Everyone in this party, or empty when nothing has been wired yet. */
    public static List<AgentId> of(PartyId party) {
        return roster.of(party);
    }

    /** Forgets the roster. Tests only — a server installs one at start and never unwinds it. */
    public static void reset() {
        roster = NOBODY;
    }
}
