package dev.luizloyola.autarkia.core.brain.task;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.luizloyola.autarkia.core.brain.act.BreakState;
import dev.luizloyola.autarkia.core.brain.knowledge.PoiKind;
import dev.luizloyola.autarkia.core.brain.knowledge.PoiMemory;
import dev.luizloyola.autarkia.core.brain.knowledge.Region;
import dev.luizloyola.autarkia.core.brain.sense.Pos;
import org.junit.jupiter.api.Test;

/**
 * Two Persons, one claim registry, against the multi-chopper deadlock: both broke the same tree
 * until neither could finish.
 */
class TreeClaimContentionTest {

    private final FakeContext alice = new FakeContext();
    private final FakeContext bob = new FakeContext();
    private final Pos anchor = new Pos(10, 64, 10);
    private final PoiMemory memory = new PoiMemory(PoiKind.TREE, anchor,
            new Region(new Pos(9, 64, 9), new Pos(11, 68, 11)), 4, false, 0);

    private void shareOneWorld() {
        bob.siteClaims = alice.siteClaims; // one registry, like one server
    }

    @Test
    void selectionSkipsTheTreeSomeoneElseIsWorking() {
        shareOneWorld();
        alice.knowledge.note(memory);
        bob.knowledge.note(memory);
        ChopKnownTree method = new ChopKnownTree();

        assertTrue(method.applicable(alice));
        method.decompose(alice); // selection is commitment: the claim lands here

        assertFalse(method.applicable(bob),
                "the only known tree is claimed — bob has no chop to offer");
    }

    @Test
    void selectionRotatesToTheNextFreeTree() {
        shareOneWorld();
        Pos farAnchor = new Pos(30, 64, 10);
        PoiMemory far = new PoiMemory(PoiKind.TREE, farAnchor,
                new Region(new Pos(29, 64, 9), new Pos(31, 68, 11)), 4, false, 0);
        alice.knowledge.note(memory);
        bob.knowledge.note(memory);
        bob.knowledge.note(far);
        bob.percepts.position = new Pos(8, 64, 8); // near tree is genuinely nearer for bob
        ChopKnownTree method = new ChopKnownTree();

        method.decompose(alice); // alice takes the near tree
        double cost = method.estimateCost(bob);

        double farDistance = Math.sqrt(22 * 22 + 0 + 2 * 2); // bob at (8,64,8) -> (30,64,10)
        assertEquals(farDistance, cost, 0.01,
                "bob prices the FAR tree — the near one is alice's");
    }

    @Test
    void aChopOnSomeoneElsesSiteRotatesAwayWithoutForgetting() {
        shareOneWorld();
        bob.percepts.blocks.placeOak(10, 10);
        bob.percepts.position = new Pos(8, 64, 8);
        bob.knowledge.note(memory);
        alice.siteClaims.claim(PoiKind.TREE, anchor, alice.self, 0); 

        TaskStatus status = new ChopTree(memory, true).tick(bob);

        assertEquals(TaskStatus.FAILED, status, "one tick: no walking to an occupied tree");
        assertEquals(1, bob.knowledge.size(), "the tree is real — the memory stays");
        assertTrue(bob.knowledge.isAvoided(PoiKind.TREE, anchor, 1), "briefly avoided, so retries rotate");
        assertTrue(bob.knowledge.isAvoided(PoiKind.TREE, anchor, ChopTree.CLAIMED_AVOID_TICKS - 1));
        assertFalse(bob.knowledge.isAvoided(PoiKind.TREE, anchor, ChopTree.AVOID_TICKS),
                "the occupied-tree avoid is the SHORT one — they'll be done soon");
    }

    @Test
    void aFinishedChopFreesTheSite() {
        shareOneWorld();
        alice.percepts.blocks.placeOak(10, 10);
        alice.percepts.position = new Pos(8, 64, 8);
        alice.knowledge.note(memory);

        ChopTree task = new ChopTree(memory, false);
        TaskStatus status = TaskStatus.RUNNING;
        for (int i = 0; i < 300 && status == TaskStatus.RUNNING; i++) {
            status = task.tick(alice);
            if (alice.breaker.state == BreakState.BREAKING) {
                Pos t = alice.breaker.target;
                alice.percepts.blocks.clear(t.x(), t.y(), t.z());
                alice.breaker.state = BreakState.FINISHED;
            }
        }

        assertEquals(TaskStatus.SUCCESS, status);
        assertTrue(bob.siteClaims.availableTo(PoiKind.TREE, anchor, bob.self, 1),
                "the happy ending released the claim");
    }

    @Test
    void aCanceledChopFreesTheSiteButKeepsTheLedgerStory() {
        shareOneWorld();
        alice.percepts.blocks.placeOak(10, 10);
        alice.percepts.position = new Pos(8, 64, 8);
        alice.knowledge.note(memory);

        ChopTree task = new ChopTree(memory, true);
        task.tick(alice); // claims on the first heartbeat
        assertFalse(bob.siteClaims.availableTo(PoiKind.TREE, anchor, bob.self, 1));

        task.cancel(alice); // a preempting drive cut in

        assertTrue(bob.siteClaims.availableTo(PoiKind.TREE, anchor, bob.self, 1),
                "a suspension frees the site — the resume re-claims or rotates cleanly");
    }
}
