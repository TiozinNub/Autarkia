package dev.luizloyola.autarkia.core.brain.board;

import dev.luizloyola.autarkia.core.brain.knowledge.PoiKind;
import dev.luizloyola.autarkia.core.brain.sense.Pos;

/**
 * One person's view of the shared work-site claims ({@link SiteClaims}), identity bound in so tasks
 * never juggle their own {@code PersonId}; callers stamp the game time they already read this tick.
 *
 * <p>A claim is GROUP state honored by the task layer: a method skips sites that are not
 * {@link #availableTo available}, a working task {@link #claim heartbeats} its site and
 * {@link #release releases} it on every exit.
 */
public interface PersonClaims {
    /**
     * Claim (or re-heartbeat) the site for this person until {@code now + }{@link
     * SiteClaims#TTL_TICKS}. Returns {@code false} when someone else's live claim holds it —
     * in which case nothing changed and the site is not hers to work.
     */
    boolean claim(PoiKind kind, Pos anchor, long now);

    /** Release the site if this person holds it; anyone else's claim is left untouched. */
    void release(PoiKind kind, Pos anchor);

    /** Whether the site is workable for this person: unclaimed, expired, or already hers. */
    boolean availableTo(PoiKind kind, Pos anchor, long now);

    /**
     * The loner's view — everything is hers, claims always succeed, releases are no-ops. The
     * default for a {@link dev.luizloyola.autarkia.core.brain.BrainContext} assembled without a
     * shared registry (tests, minimal rigs): a person with no group is a group of one.
     */
    PersonClaims SOLO = new PersonClaims() {
        @Override
        public boolean claim(PoiKind kind, Pos anchor, long now) {
            return true;
        }

        @Override
        public void release(PoiKind kind, Pos anchor) {
        }

        @Override
        public boolean availableTo(PoiKind kind, Pos anchor, long now) {
            return true;
        }
    };
}
