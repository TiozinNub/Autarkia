package dev.luizloyola.autarkia.core.board;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.luizloyola.anima.core.agent.AgentId;
import dev.luizloyola.anima.core.brain.BrainContext;
import dev.luizloyola.anima.core.brain.board.WorkItem;
import dev.luizloyola.anima.core.brain.board.WorkSource;
import dev.luizloyola.anima.core.brain.history.Deed;
import dev.luizloyola.anima.core.brain.sense.Pos;
import dev.luizloyola.anima.core.brain.task.Method;
import dev.luizloyola.anima.core.brain.task.ObtainItem;
import dev.luizloyola.anima.core.brain.task.Producers;
import dev.luizloyola.anima.core.brain.task.Task;
import dev.luizloyola.anima.core.inv.ItemSpec;
import dev.luizloyola.anima.core.social.PartyId;
import java.util.List;
import java.util.OptionalDouble;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ExpeditionTest {

    private static final PartyId PARTY = PartyId.of(UUID.randomUUID());
    private static final AgentId DORIS = AgentId.random();
    private static final AgentId EMILY = AgentId.random();

    private static final ItemSpec STONE = ItemSpec.register(
            new ItemSpec("expedition-test-stone", id -> id.startsWith("test:stone")));

    /** Stone only a body standing east of x = 100 knows of. */
    private static final ItemSpec EAST_STONE = ItemSpec.register(
            new ItemSpec("expedition-test-east-stone", id -> id.startsWith("test:east_stone")));

    // Registered once and never reset: Producers.reset() also drops what other classes register
    // when they load, and StrandedDigTest failed for it.
    static {
        Producers.register(STONE, id -> true, wanted -> new Method() {
            @Override
            public boolean applicable(BrainContext ctx) {
                return true;
            }

            @Override
            public double estimateCost(BrainContext ctx) {
                return 220;
            }

            @Override
            public List<Task> decompose(BrainContext ctx) {
                return List.of();
            }

            @Override
            public String describe() {
                return "mine the far stone";
            }
        });
    }

    static {
        Producers.register(EAST_STONE, id -> true, wanted -> new Method() {
            @Override
            public boolean applicable(BrainContext ctx) {
                return ctx.percepts().position().x() > 100;
            }

            @Override
            public double estimateCost(BrainContext ctx) {
                return 220;
            }

            @Override
            public List<Task> decompose(BrainContext ctx) {
                return List.of();
            }

            @Override
            public String describe() {
                return "mine the east stone";
            }
        });
    }

    @BeforeEach
    void setUp() {
        Splits.register(CarrySplit.INSTANCE);
    }

    @Test
    void onlyOneWhoKnowsTheWayIsOfferedTheLead() {
        Expedition far = new Expedition(EAST_STONE, PARTY);
        far.need(DORIS, "put minecraft:furnace down", 8, 0.52, Set.of("minecraft:furnace"), 0);
        far.tick(0);
        BoardBrainContext bia = atHome();
        BoardBrainContext ana = atHome();
        ana.standAt(new Pos(150, 64, 0));

        assertFalse(far.offerableTo(far.open().get(0), EMILY, bia), "she knows no such stone; she can go along");
        assertTrue(far.offerableTo(far.open().get(0), DORIS, ana));
    }

    @Test
    void anOperatorsOrderDoesNotLapse() {
        Expedition far = new Expedition(STONE, PARTY);
        far.need(new Expedition.Need(DORIS, "asked by Server", 40, 0.4, Set.of(), 0, true), 0);
        far.tick(10 * Expedition.NEED_LAPSE);
        assertEquals(80, far.haul(), "nothing will fail again to say it is still wanted");
    }

    private static BoardBrainContext atHome() {
        BoardBrainContext ctx = new BoardBrainContext();
        ctx.depot = new Pos(0, 64, 0);
        return ctx;
    }

    /** Doris on the forest, 2026-10-02: a furnace and four stone tools, every one priced out. */
    private static Expedition dorisNeeds() {
        Expedition far = new Expedition(STONE, PARTY);
        far.need(DORIS, "put minecraft:furnace down", 8, 0.52, Set.of("minecraft:furnace"), 0);
        far.need(DORIS, "make a stone axe", 3, 0.46, Set.of("minecraft:stone_axe"), 0);
        far.need(DORIS, "make a stone pickaxe", 3, 0.46, Set.of("minecraft:stone_pickaxe"), 0);
        far.need(DORIS, "make a stone shovel", 1, 0.46, Set.of("minecraft:stone_shovel"), 0);
        far.need(DORIS, "make a stone hoe", 2, 0.46, Set.of("minecraft:stone_hoe"), 0);
        far.tick(0);
        return far;
    }

    private static WorkItem takes(Expedition far, AgentId who, BoardBrainContext ctx) {
        WorkItem trip = far.realise(far.open().get(0), who, ctx);
        far.claimed(trip, who);
        return trip;
    }

    // ── the haul ─────────────────────────────────────────────────────────────────────────────

    @Test
    void everyNeedOfOneResourceGoesInOneHaulTwiceOver() {
        Expedition far = dorisNeeds();
        assertEquals(34, far.haul(), "17 stone asked, open needs × 2 (Luiz, 2026-10-02)");
        assertEquals(0.52, far.priority(), "it bids what its most pressing need does");

        far.need(DORIS, "make a stone axe", 3, 0.46, Set.of("minecraft:stone_axe"), 500);
        assertEquals(34, far.haul(), "an item failing again is the same need, not another");
    }

    @Test
    void theTripGoesFarForWhatTheNeedsWereMaking() {
        Expedition far = dorisNeeds();
        WorkItem trip = takes(far, DORIS, atHome());

        assertEquals(OptionalDouble.of(Expedition.REACH), trip.tolerance(), "past every budget a need earns");
        ExpeditionErrand errand = assertInstanceOf(ExpeditionErrand.class, trip.root());
        assertEquals(34, errand.count());
        assertEquals(0, errand.muster(), "one pack holds the haul: nobody is waited for");
        assertEquals(null, errand.leader());
        assertEquals(Set.of("minecraft:furnace", "minecraft:stone_axe", "minecraft:stone_pickaxe",
                        "minecraft:stone_shovel", "minecraft:stone_hoe"), errand.pursued(),
                "what lets the trip seek stone in an age that makes nothing else of it");
        assertEquals(List.of(trip), far.open(), "one trip at a time");
    }

    @Test
    void aTripThatCameHomeEndsIt() {
        Expedition far = dorisNeeds();
        WorkItem trip = takes(far, DORIS, atHome());
        far.completed(trip, atHome());
        assertTrue(far.finished());
    }

    @Test
    void aFailedTripWaitsLongerEachTime() {
        Expedition far = dorisNeeds();
        BoardBrainContext ctx = atHome();
        far.failed(takes(far, DORIS, ctx), ctx);

        far.tick(599);
        assertFalse(far.offerableTo(far.open().get(0), DORIS, ctx), "600 ticks after the first failure");
        far.tick(600);
        assertTrue(far.offerableTo(far.open().get(0), DORIS, ctx));
        assertEquals(1200, Expedition.cooldownAfter(2));
        assertEquals(4800, Expedition.cooldownAfter(9));
    }

    @Test
    void withNoHomeNobodyGoes() {
        Expedition far = dorisNeeds();
        assertFalse(far.offerableTo(far.open().get(0), DORIS, new BoardBrainContext()),
                "the haul has nowhere to go");
    }

    @Test
    void aNeedThatStopsFailingLeaves() {
        Expedition far = dorisNeeds();
        far.need(DORIS, "make a stone axe", 3, 0.46, Set.of("minecraft:stone_axe"), 5000);
        far.tick(Expedition.NEED_LAPSE + 1);
        assertEquals(6, far.haul(), "only the axe failed again");
        far.tick(5000 + Expedition.NEED_LAPSE + 1);
        assertTrue(far.finished(), "met another way, or withdrawn: nothing left to fetch");
    }

    // ── company ──────────────────────────────────────────────────────────────────────────────

    /** Every storage slot full but one, holding {@code 64 - room} of the stone. */
    private static BoardBrainContext roomFor(int room) {
        BoardBrainContext ctx = atHome();
        for (int slot = 0; slot < dev.luizloyola.anima.core.inv.Inventory.ARMOR_START; slot++) {
            ctx.inventory().set(slot, dev.luizloyola.anima.core.inv.ItemStack.of("minecraft:dirt", 64, 64));
        }
        ctx.inventory().set(0, dev.luizloyola.anima.core.inv.ItemStack.of("test:stone_a", 64 - room, 64));
        return ctx;
    }

    @Test
    void aHaulOnePackCannotHoldIsSharedWithWhoeverGoesAlong() {
        Expedition far = dorisNeeds();
        far.tick(100);
        WorkItem lead = takes(far, DORIS, roomFor(20));
        ExpeditionErrand leading = assertInstanceOf(ExpeditionErrand.class, lead.root());
        assertEquals(20, leading.count());
        assertEquals(Expedition.MUSTER_TICKS, leading.muster(), "it waits for company at HOME first");

        WorkItem offer = far.open().get(1);
        assertFalse(far.offerableTo(offer, DORIS, atHome()), "the one leading does not go along with itself");
        assertTrue(far.offerableTo(offer, EMILY, atHome()));
        WorkItem share = far.realise(offer, EMILY, atHome());
        far.claimed(share, EMILY);
        ExpeditionErrand along = assertInstanceOf(ExpeditionErrand.class, share.root());
        assertEquals(14, along.count(), "what the leader's pack left of the 34");
        assertEquals(dev.luizloyola.anima.core.brain.sense.BeingId.of(DORIS), along.leader());
        assertEquals(List.of(lead, share), far.open(), "nothing left to share");

        far.completed(lead, atHome());
        assertFalse(far.finished(), "Emily is still out with her share");
        far.completed(share, atHome());
        assertTrue(far.finished());
    }

    @Test
    void theCompanyOfferClosesWithTheMuster() {
        Expedition far = dorisNeeds();
        far.tick(100);
        takes(far, DORIS, roomFor(20));
        assertEquals(2, far.open().size());
        far.tick(100 + Expedition.MUSTER_TICKS);
        assertEquals(1, far.open().size(), "the leader has gone; nobody can catch up");
    }

    @Test
    void aCompanionWhoseShareFailedSitsTheOfferOut() {
        Expedition far = dorisNeeds();
        far.tick(100);
        takes(far, DORIS, roomFor(20));
        BoardBrainContext ctx = atHome();
        WorkItem share = far.realise(far.open().get(1), EMILY, ctx);
        far.claimed(share, EMILY);
        far.failed(share, ctx);

        far.tick(200);
        assertFalse(far.offerableTo(far.open().get(1), EMILY, ctx), "600 ticks after the failure");
    }

    @Test
    void aRestartKeepsWhoWentAlong() {
        Expedition far = dorisNeeds();
        far.tick(100);
        takes(far, DORIS, roomFor(20));
        WorkItem share = far.realise(far.open().get(1), EMILY, atHome());
        far.claimed(share, EMILY);

        Expedition back = Expedition.restore(far.snapshot(), 0).orElseThrow();
        assertEquals(far.snapshot(), back.snapshot());
        WorkItem again = back.itemFor(new WorkKey.ForMember(WorkKey.EXPEDITION_COMPANY, EMILY)).orElseThrow();
        back.claimed(again, EMILY);
        back.holdsRestored();
        assertTrue(back.open().contains(again));

        Expedition unheld = Expedition.restore(far.snapshot(), 0).orElseThrow();
        unheld.holdsRestored();
        assertTrue(unheld.itemFor(new WorkKey.ForMember(WorkKey.EXPEDITION_COMPANY, EMILY)).isEmpty(),
                "a share nobody came back for is dropped");
    }

    // ── a restart ────────────────────────────────────────────────────────────────────────────

    @Test
    void aRestartKeepsTheNeedsTheTripAndThePacing() {
        Expedition far = dorisNeeds();
        BoardBrainContext ctx = atHome();
        far.failed(takes(far, DORIS, ctx), ctx);
        WorkItem trip = takes(far, EMILY, ctx);

        Expedition back = Expedition.restore(far.snapshot(), 0).orElseThrow();
        assertEquals(far.snapshot(), back.snapshot());
        WorkItem again = back.itemFor(new WorkKey.ForMember(WorkKey.EXPEDITION, EMILY)).orElseThrow();
        back.claimed(again, EMILY);
        back.holdsRestored();
        assertEquals(List.of(again), back.open(), "the trip goes back to who was walking it");

        Expedition unheld = Expedition.restore(far.snapshot(), 0).orElseThrow();
        unheld.holdsRestored();
        assertEquals(1, unheld.open().size());
        assertTrue(unheld.open().get(0) != trip && unheld.keyOf(unheld.open().get(0)).isEmpty(),
                "a trip nobody came back for is dropped, and the expedition is on offer again");
    }

    // ── the party board ──────────────────────────────────────────────────────────────────────

    private record Need(String what, double priority) implements WorkItem {
        @Override
        public Task root() {
            throw new UnsupportedOperationException();
        }

        @Override
        public String describe() {
            return what;
        }

        @Override
        public Deed doing() {
            throw new UnsupportedOperationException();
        }
    }

    @Test
    void thePartyPostsOneExpeditionPerSource() {
        PartyBoard board = new PartyBoard(PARTY);
        WorkSource doris = board.viewFor(() -> DORIS);
        BoardBrainContext ctx = atHome();

        double cap = dev.luizloyola.anima.core.brain.WorkToleranceCurve.CAP;
        doris.pricedOutOf(new Need("make a stone axe", 0.46),
                new ObtainItem(STONE, 3, Set.of("minecraft:stone_axe")), 141, ctx);
        assertTrue(board.projects().isEmpty(), "below the cap a longer wait may still afford it");

        doris.pricedOutOf(new Need("put minecraft:furnace down", 0.52),
                new ObtainItem(ItemSpec.anyOf(Set.of("test:stone_a", "test:stone_b")), 8,
                        Set.of("minecraft:furnace")), cap, ctx);
        doris.pricedOutOf(new Need("make a stone axe", 0.46),
                new ObtainItem(STONE, 3, Set.of("minecraft:stone_axe")), 141, ctx);
        doris.pricedOutOf(new Need("make an iron axe", 0.46),
                new ObtainItem(ItemSpec.anyOf(Set.of("test:iron")), 3, Set.of("minecraft:iron_axe")), cap, ctx);

        List<Project> posted = board.projects();
        assertEquals(1, posted.size(), "both stones are one source; nobody makes iron here");
        Expedition far = assertInstanceOf(Expedition.class, posted.get(0));
        assertEquals(STONE, far.resource());
        assertEquals(22, far.haul(), "the axe joins below the cap once a trip is going");
    }
}
