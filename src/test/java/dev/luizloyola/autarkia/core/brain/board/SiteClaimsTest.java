package dev.luizloyola.autarkia.core.brain.board;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.luizloyola.autarkia.core.brain.knowledge.PoiKind;
import dev.luizloyola.autarkia.core.brain.sense.Pos;
import dev.luizloyola.autarkia.core.person.PersonId;
import org.junit.jupiter.api.Test;

/**
 * The claim registry's contract: heartbeats not locks — a claim excludes others only while
 * fresh, re-claiming your own refreshes, releasing is holder-only, and a lapsed claim frees
 * the site without anyone's help (the dead-claimant story).
 */
class SiteClaimsTest {

    private final SiteClaims claims = new SiteClaims();
    private final PersonId alice = PersonId.random();
    private final PersonId bob = PersonId.random();
    private final Pos anchor = new Pos(10, 64, 10);

    @Test
    void aLiveClaimExcludesEveryoneElse() {
        assertTrue(claims.claim(PoiKind.TREE, anchor, alice, 0));
        assertFalse(claims.claim(PoiKind.TREE, anchor, bob, 1), "bob can't take alice's site");
        assertFalse(claims.availableTo(PoiKind.TREE, anchor, bob, 1));
        assertTrue(claims.availableTo(PoiKind.TREE, anchor, alice, 1), "hers stays hers");
        assertTrue(claims.claim(PoiKind.TREE, anchor, alice, 1), "re-claiming your own succeeds");
    }

    @Test
    void aClaimIsAHeartbeatNotALock() {
        claims.claim(PoiKind.TREE, anchor, alice, 0);
        long lastGasp = SiteClaims.TTL_TICKS - 1;
        assertFalse(claims.availableTo(PoiKind.TREE, anchor, bob, lastGasp), "still fresh");
        assertTrue(claims.availableTo(PoiKind.TREE, anchor, bob, SiteClaims.TTL_TICKS),
                "no heartbeat for a full TTL: the claimant is gone, the site frees itself");
        assertTrue(claims.claim(PoiKind.TREE, anchor, bob, SiteClaims.TTL_TICKS));
    }

    @Test
    void reclaimingRefreshesTheHeartbeat() {
        claims.claim(PoiKind.TREE, anchor, alice, 0);
        claims.claim(PoiKind.TREE, anchor, alice, 500); // the working tick's re-claim
        assertFalse(claims.availableTo(PoiKind.TREE, anchor, bob, SiteClaims.TTL_TICKS + 400),
                "the refresh moved the lapse out, not the original claim time");
    }

    @Test
    void releaseIsHolderOnly() {
        claims.claim(PoiKind.TREE, anchor, alice, 0);
        claims.release(PoiKind.TREE, anchor, bob); // not his to free
        assertFalse(claims.availableTo(PoiKind.TREE, anchor, bob, 1));
        claims.release(PoiKind.TREE, anchor, alice);
        assertTrue(claims.availableTo(PoiKind.TREE, anchor, bob, 1), "a clean exit frees it now");
    }

    @Test
    void thePersonBoundViewSpeaksForItsPerson() {
        PersonClaims hers = claims.forPerson(alice);
        PersonClaims his = claims.forPerson(bob);
        assertTrue(hers.claim(PoiKind.TREE, anchor, 0));
        assertFalse(his.claim(PoiKind.TREE, anchor, 1));
        assertFalse(his.availableTo(PoiKind.TREE, anchor, 1));
        hers.release(PoiKind.TREE, anchor);
        assertTrue(his.availableTo(PoiKind.TREE, anchor, 2));
    }
}
