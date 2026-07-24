package dev.luizloyola.autarkia.core.brain.board;

import dev.luizloyola.autarkia.core.brain.knowledge.PoiKind;
import dev.luizloyola.autarkia.core.brain.sense.Pos;
import dev.luizloyola.autarkia.core.person.PersonId;
import java.util.HashMap;
import java.util.Map;

/**
 * The shared "who is working what" registry — layer 3's first genuinely GROUP-scoped piece, added
 * after two choppers on one tree were seen stealing each other's logs and felling each other's
 * scaffolding. One instance per world; slice 3's group claim board will absorb and generalize it.
 *
 * <p><b>Claims are heartbeats, not locks.</b> A claim lives {@link #TTL_TICKS} past its last
 * {@link #claim} and a working task re-claims every tick, so death, despawn or a crash frees the
 * site within one TTL instead of wedging it shut. Release is polite form, freeing it at once.
 *
 * <p>Transient: a fresh boot starts unclaimed and the first tick's claims rebuild the truth. Pure
 * core, single-threaded by contract (the server thread), so a plain map suffices.
 */
public final class SiteClaims {
    /**
     * Ticks a claim survives past its last heartbeat — long enough to ride out a suspension
     * (a meal mid-chop), short enough that a dead claimant frees the site within half a minute.
     */
    public static final int TTL_TICKS = 600;

    private record Key(PoiKind kind, Pos anchor) {
    }

    private record Claim(PersonId who, long untilTick) {
    }

    private final Map<Key, Claim> claims = new HashMap<>();

    /** See {@link PersonClaims#claim}: succeeds unless someone ELSE's live claim holds the site. */
    public boolean claim(PoiKind kind, Pos anchor, PersonId who, long now) {
        Key key = new Key(kind, anchor);
        Claim held = claims.get(key);
        if (held != null && held.untilTick() > now && !held.who().equals(who)) {
            return false;
        }
        claims.put(key, new Claim(who, now + TTL_TICKS));
        return true;
    }

    /** See {@link PersonClaims#release}: only the holder's own release removes anything. */
    public void release(PoiKind kind, Pos anchor, PersonId who) {
        Key key = new Key(kind, anchor);
        Claim held = claims.get(key);
        if (held != null && held.who().equals(who)) {
            claims.remove(key);
        }
    }

    /** See {@link PersonClaims#availableTo}: unclaimed, expired, or already this person's. */
    public boolean availableTo(PoiKind kind, Pos anchor, PersonId who, long now) {
        Claim held = claims.get(new Key(kind, anchor));
        return held == null || held.untilTick() <= now || held.who().equals(who);
    }

    /** This person's bound view — what a {@code BrainContext} hands the task machinery. */
    public PersonClaims forPerson(PersonId who) {
        return new PersonClaims() {
            @Override
            public boolean claim(PoiKind kind, Pos anchor, long now) {
                return SiteClaims.this.claim(kind, anchor, who, now);
            }

            @Override
            public void release(PoiKind kind, Pos anchor) {
                SiteClaims.this.release(kind, anchor, who);
            }

            @Override
            public boolean availableTo(PoiKind kind, Pos anchor, long now) {
                return SiteClaims.this.availableTo(kind, anchor, who, now);
            }
        };
    }

    /** Live + lapsed entries currently stored — the debug commands' number, not a semantic one. */
    public int size() {
        return claims.size();
    }
}
