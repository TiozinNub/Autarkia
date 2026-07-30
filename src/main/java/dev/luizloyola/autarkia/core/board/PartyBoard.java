package dev.luizloyola.autarkia.core.board;

import dev.luizloyola.anima.core.social.PartyId;

/**
 * One party's board — the shared place a group's work is posted, reached by every member through
 * their own {@link Board#viewFor view}.
 *
 * <p>It hangs off a {@link PartyId}, not a member: members come and go, a party can be unloaded,
 * and the work still exists and still belongs to somebody. It ticks server-side and entity-free, so
 * a party evolving while nobody is loaded needs no rework.
 *
 * <p>Empty in ladder step 2 by design — nothing posts until the clear-area project arrives. What
 * lands now is the id, the host that ticks it, and the per-member view.
 */
public final class PartyBoard extends Board {

    private final PartyId party;

    public PartyBoard(PartyId party) {
        this.party = party;
    }

    public PartyId party() {
        return party;
    }

    @Override
    public String label() {
        return "party";
    }

    /**
     * One beat of the board's own slow, entity-free thinking — run by the server-side host on a
     * staggered cadence, with no agent's context.
     *
     * <p>Today it only closes what is satisfied; party projects and step 3's lease expiry hang off
     * this same beat. {@code now} is an argument from the start so neither changes the signature.
     */
    public void tick(long now) {
        closeFinished();
    }
}
