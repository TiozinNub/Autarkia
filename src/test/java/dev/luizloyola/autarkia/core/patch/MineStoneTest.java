package dev.luizloyola.autarkia.core.patch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.luizloyola.anima.core.agent.AgentId;
import dev.luizloyola.anima.core.brain.gate.Act;
import dev.luizloyola.anima.core.brain.gate.Gate;
import dev.luizloyola.anima.core.brain.knowledge.AgentKnowledge;
import dev.luizloyola.anima.core.brain.knowledge.FakeProbe;
import dev.luizloyola.anima.core.brain.knowledge.PoiMemory;
import dev.luizloyola.anima.core.brain.knowledge.Region;
import dev.luizloyola.anima.core.brain.sense.Pos;
import dev.luizloyola.anima.core.brain.task.BreakBlock;
import dev.luizloyola.anima.core.brain.task.FakeContext;
import dev.luizloyola.anima.core.brain.task.GoTo;
import dev.luizloyola.anima.core.brain.task.ObtainItem;
import dev.luizloyola.anima.core.brain.task.Task;
import dev.luizloyola.anima.core.brain.task.TaskStatus;
import dev.luizloyola.anima.core.brain.task.Try;
import dev.luizloyola.anima.core.inv.ItemStack;
import dev.luizloyola.autarkia.core.board.Stock;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Stone from where it shows: a remembered patch, a pickaxe first, a barren patch rested, and a flat
 * patch's top layer when nothing stands.
 */
class MineStoneTest {

    private final FakeContext ctx = new FakeContext();
    private final Pos patch = new Pos(12, 64, 0);

    @BeforeEach
    void setUp() {
        ctx.percepts.position = new Pos(0, 64, 0);
        Stock.furnaceStoneBy(id -> id.equals("minecraft:cobblestone"));
    }

    @AfterEach
    void tearDown() {
        Gate.install(Gate.OPEN);
        Stock.furnaceStoneBy(id -> false);
    }

    private static final int G = FakeProbe.GROUND_Y;

    /** Stone from the ground up to {@code top} at the column. */
    private void stone(int x, int z, int top) {
        for (int y = G; y <= top; y++) {
            ctx.percepts.blocks.set(x, y, z, Landmarks.STONE);
        }
    }

    /** The patch remembered, with an outcrop two above the flat ground at its anchor. */
    private void remember() {
        stone(12, 0, G + 2);
        ctx.knowledge.note(new PoiMemory(Landmarks.STONE_POI, patch,
                new Region(new Pos(10, 64, -2), new Pos(14, 64, 2)), 12, false, 0L),
                AgentKnowledge.maxPerKind(ctx.profile()));
    }

    private static MineStone forStone() {
        return new MineStone(Stock.FURNACE_STONE);
    }

    @Test
    void withNoStoneKnownThereIsNoWay() {
        assertFalse(forStone().applicable(ctx));
    }

    @Test
    void aRememberedPatchIsAWayPricedByTheWalkAndTheWork() {
        remember();
        assertTrue(forStone().applicable(ctx));
        assertEquals(12 + MineStone.WORK, forStone().estimateCost(ctx), 1e-9);
    }

    @Test
    void aBodyWithNoPickaxeGetsOneFirst() {
        remember();
        List<Task> plan = forStone().decompose(ctx);
        assertEquals(Stock.PICKAXES, assertInstanceOf(ObtainItem.class, plan.get(0)).spec(),
                "stone mined bare-handed drops nothing");
        assertInstanceOf(GoTo.class, plan.get(1));
        assertInstanceOf(MinePatch.class, plan.get(2));
    }

    @Test
    void aBodyWithOneGoesStraightThere() {
        remember();
        ctx.percepts.inventory.set(0, ItemStack.of("minecraft:wooden_pickaxe", 1, 1));
        assertInstanceOf(GoTo.class, forStone().decompose(ctx).get(0));
    }

    @Test
    void aTripRestsThePatchOnceWhatShowedIsMined() {
        remember();
        forStone().decompose(ctx);
        for (int y = G + 1; y <= G + 2; y++) {
            ctx.percepts.blocks.clear(12, y, 0);
        }
        assertFalse(forStone().applicable(ctx), "what showed has been mined; come back later");
    }

    /** Luiz, 2026-10-02: a far trip to a lone patch came home with 8, the rest left standing. */
    @Test
    void aRestedPatchWithStoneStillStandingIsMinedAgain() {
        remember();
        forStone().decompose(ctx);
        assertTrue(forStone().applicable(ctx), "the stone is in sight and still there");
    }

    @Test
    void aRestedPatchOutOfSightWaitsOutItsRest() {
        remember();
        forStone().decompose(ctx);
        ctx.percepts.blocks.markUnloaded(12, 0);
        assertFalse(forStone().applicable(ctx), "nobody can tell from here whether anything is left");
    }

    @Test
    void aBarrenPatchStaysStruckForItsDay() {
        remember();
        ctx.knowledge.avoid(Landmarks.STONE_POI, patch, ctx.percepts.time() + MinePatch.BARREN_TICKS);
        assertFalse(forStone().applicable(ctx), "granite stands there, and gives a furnace nothing");
    }

    @Test
    void aBodyTheGateKeepsFromDiggingDoesNot() {
        remember();
        Gate.install(new Gate.Policy() {
            @Override
            public Optional<String> refuseItem(AgentId body, String itemId) {
                return Optional.empty();
            }

            @Override
            public Optional<String> refuseAct(AgentId body, Act act) {
                return act.equals(MineStone.ACT) ? Optional.of("not in the Wood Age") : Optional.empty();
            }
        });
        ctx.gate = Gate.viewFor(ctx.self, ctx.journal());
        assertFalse(forStone().applicable(ctx));
    }

    private List<Pos> exposed() {
        return new MinePatch(patch, new Region(new Pos(8, G, -4), new Pos(16, G + 3, 4)),
                Stock.FURNACE_STONE).exposed(ctx);
    }

    /** Mined first, the anchor took the whole patch out of mind: 8 home from 49 (2026-10-02). */
    @Test
    void theAnchorIsMinedLastSoThePatchIsRememberedUntilItIsGone() {
        Pos anchor = new Pos(10, G + 1, 0);
        for (int x = 10; x <= 13; x++) {
            for (int z = -1; z <= 1; z++) {
                stone(x, z, G + 1);
            }
        }
        ctx.percepts.position = new Pos(9, 64, 0);
        MinePatch mine = new MinePatch(anchor, new Region(new Pos(8, G, -4), new Pos(16, G + 3, 4)),
                Stock.FURNACE_STONE);

        assertFalse(mine.exposed(ctx).contains(anchor), "twelve showing: the nearest is left for last");

        for (int x = 10; x <= 13; x++) {
            for (int z = -1; z <= 1; z++) {
                if (x != 10 || z != 0) {
                    ctx.percepts.blocks.clear(x, G + 1, z);
                }
            }
        }
        assertEquals(List.of(anchor), mine.exposed(ctx), "and taken once nothing else is left");
    }

    /**
     * A one-high outcrop 11×11 at x 20..30, remembered by its corner only — the sense's partial
     * patch — and HOME's one chunk round it, or far off.
     */
    private MinePatch flatOutcrop(boolean homeHere) {
        for (int x = 20; x <= 30; x++) {
            for (int z = -5; z <= 5; z++) {
                stone(x, z, G + 1);
            }
        }
        Pos home = homeHere ? new Pos(20, 64, 0) : new Pos(-500, 64, 0);
        ctx.depot = java.util.Optional.of(new dev.luizloyola.anima.core.store.Depot.Site(home, java.util.Set.of(
                new dev.luizloyola.anima.core.territory.ChunkKey(dev.luizloyola.anima.core.territory.ChunkKey.OVERWORLD,
                        Math.floorDiv(home.x(), 16), Math.floorDiv(home.z(), 16)))));
        ctx.percepts.position = new Pos(19, G + 1, 0);
        return new MinePatch(new Pos(22, G + 1, -2), new Region(new Pos(20, G, -5), new Pos(25, G + 1, 0)),
                Stock.FURNACE_STONE);
    }

    /** Luiz, 2026-10-03: a far trip to a flat outcrop got its edge only, two or three a visit. */
    @Test
    void awayFromHomeAFlatOutcropsWholeTopLayerIsTakenEdgeFirst() {
        List<Pos> cells = flatOutcrop(false).exposed(ctx);

        assertEquals(8, cells.size(), "a full pass, not the two or three the edge shows");
        assertFalse(cells.contains(new Pos(22, G + 1, -2)), "the anchor is left for last");
        assertTrue(cells.stream().allMatch(cell -> cell.y() == G + 1), "never below the land round it");
    }

    @Test
    void nearHomeAnOutcropIsStillCutBackFromItsEdgeOnly() {
        List<Pos> cells = flatOutcrop(true).exposed(ctx);
        assertTrue(cells.size() < 8, "only what stands above the ground round it: " + cells);
        assertTrue(cells.stream().noneMatch(cell -> cell.x() > 20 && cell.z() > -5),
                "no quarry beside the house: " + cells);
    }

    @Test
    void aFirstBlockSomebodyElseTookDoesNotJudgeTheStone() {
        remember();
        MinePatch.Yield yield = new MinePatch.Yield(patch, Stock.FURNACE_STONE, 0, 0);
        assertEquals(TaskStatus.FAILED, yield.tick(ctx), "nothing came of it");
        assertFalse(ctx.knowledge.isAvoided(Landmarks.STONE_POI, patch, ctx.percepts.time() + 1),
                "a companion took the block; the patch is no less stone");

        ctx.percepts.inventory.set(0, ItemStack.of("minecraft:granite", 1, 64));
        assertEquals(TaskStatus.FAILED, yield.tick(ctx));
        assertTrue(ctx.knowledge.isAvoided(Landmarks.STONE_POI, patch, ctx.percepts.time() + 1), "granite came of it");
    }

    @Test
    void onlyTheTopOfAnOutcropIsMinedNearestFirst() {
        stone(12, 0, G + 2);
        stone(11, 1, G + 2);
        ctx.percepts.position = new Pos(10, 64, 0);

        List<Pos> cells = exposed();

        assertEquals(new Pos(11, G + 2, 1), cells.get(0));
        assertTrue(cells.contains(new Pos(12, G + 2, 0)), "the top of the column");
        assertFalse(cells.contains(new Pos(12, G + 1, 0)), "under another stone: not showing");
    }

    @Test
    void anOutcropIsMinedDownToTheGroundRoundItAndNoFurther() {
        stone(12, 0, G + 2);
        assertEquals(List.of(new Pos(12, G + 2, 0)), exposed());
        ctx.percepts.blocks.clear(12, G + 2, 0);
        assertEquals(List.of(new Pos(12, G + 1, 0)), exposed(), "one above the flat is a bump too");
        ctx.percepts.blocks.clear(12, G + 1, 0);
        assertEquals(List.of(), exposed(), "level with the flat: left");
    }

    @Test
    void flatStoneHasNothingStanding() {
        for (int x = 6; x <= 18; x++) {
            for (int z = -6; z <= 6; z++) {
                stone(x, z, G);
            }
        }
        assertEquals(List.of(), exposed());
    }

    /** A stone field one above the flat, wide enough that the patch's own edges are not bumps. */
    private void flatField(int midX) {
        for (int x = midX - 9; x <= midX + 9; x++) {
            for (int z = -9; z <= 9; z++) {
                stone(x, z, G + 1);
            }
        }
    }

    private void rememberAt(Pos anchor) {
        ctx.knowledge.note(new PoiMemory(Landmarks.STONE_POI, anchor,
                new Region(new Pos(anchor.x() - 2, G + 1, -2), new Pos(anchor.x() + 2, G + 1, 2)), 25, false, 0L),
                AgentKnowledge.maxPerKind(ctx.profile()));
    }

    private MinePatch tripOf(List<Task> plan) {
        return assertInstanceOf(MinePatch.class, plan.get(plan.size() - 1));
    }

    private static List<Pos> broken(List<Task> steps) {
        return steps.stream().filter(Try.class::isInstance).map(t -> ((Try) t).attempt())
                .filter(BreakBlock.class::isInstance).map(t -> ((BreakBlock) t).target()).toList();
    }

    @Test
    void withOnlyFlatPatchesTheLastResortMinesTheirTopLayer() {
        flatField(12);
        rememberAt(patch);
        ctx.percepts.inventory.set(0, ItemStack.of("minecraft:wooden_pickaxe", 1, 1));

        assertTrue(forStone().applicable(ctx), "the furnace's stone is not left for want of a bump");
        MinePatch trip = tripOf(forStone().decompose(ctx));
        assertTrue(trip.lastResort());
        List<Task> steps = trip.methods().get(0).decompose(ctx);
        List<Pos> cells = broken(steps);

        assertEquals(MinePatch.MOST, cells.size(), "eight stone: a furnace's worth");
        assertTrue(steps.stream().anyMatch(MinePatch.Yield.class::isInstance));
        ctx.percepts.inventory.set(1, ItemStack.of("minecraft:cobblestone", 1, 8));
        assertEquals(TaskStatus.SUCCESS, new MinePatch.Yield(patch, Stock.FURNACE_STONE, 0).tick(ctx));
    }

    @Test
    void aLastResortPassNeverDigsBelowOneLayer() {
        flatField(12);
        // Three of the nine columns round a one-column patch were cut by an earlier pass.
        ctx.percepts.blocks.clear(11, G + 1, 0);
        ctx.percepts.blocks.clear(12, G + 1, 1);
        ctx.percepts.blocks.clear(13, G + 1, -1);
        MinePatch trip = new MinePatch(patch, new Region(new Pos(12, G + 1, 0), new Pos(12, G + 1, 0)),
                Stock.FURNACE_STONE, true);

        List<Pos> cells = trip.exposed(ctx);

        assertEquals(6, cells.size());
        assertTrue(cells.stream().allMatch(c -> c.y() == G + 1), "the surface only: " + cells);
        assertEquals(cells.size(), cells.stream().map(c -> c.x() * 1000 + c.z()).distinct().count(),
                "one block a column");
    }

    @Test
    void theLastResortCutsWhatIsLeftLeastSunkFirst() {
        flatField(12);
        // A dip beside the patch: the cells on its rim stand nearest to proud of the ground round them.
        for (int z = -9; z <= 9; z++) {
            ctx.percepts.blocks.clear(8, G + 1, z);
            ctx.percepts.blocks.clear(7, G + 1, z);
        }
        List<Pos> cells = MinePatch.layer(ctx.percepts.blocks, new Region(new Pos(10, G + 1, -3),
                new Pos(14, G + 1, 3)), new Pos(20, 64, 0));
        assertTrue(cells.stream().allMatch(c -> c.x() <= 10), "the rim first: " + cells);
    }

    @Test
    void aPatchWithStoneStandingWinsOverTheLastResort() {
        flatField(12);
        rememberAt(patch);
        Pos bump = new Pos(-40, G + 2, 0);
        stone(-40, 0, G + 2);
        ctx.knowledge.note(new PoiMemory(Landmarks.STONE_POI, bump,
                new Region(new Pos(-42, G, -2), new Pos(-38, G + 2, 2)), 3, false, 0L),
                AgentKnowledge.maxPerKind(ctx.profile()));

        MinePatch trip = tripOf(forStone().decompose(ctx));

        assertEquals(bump, trip.anchor(), "the bump, though the flat field is nearer");
        assertFalse(trip.lastResort());
    }

    @Test
    void aHolesRimIsMinedAndItsFloorIsNot() {
        // The plateau runs past the patch so its corners, which are cut too, lie outside it.
        for (int x = 2; x <= 22; x++) {
            for (int z = -10; z <= 10; z++) {
                int ring = Math.max(Math.abs(x - 12), Math.abs(z));
                stone(x, z, ring == 0 ? G : ring == 1 ? G + 3 : G + 1);
            }
        }
        List<Pos> cells = exposed();
        assertFalse(cells.isEmpty());
        assertTrue(cells.stream().allMatch(c -> c.y() == G + 3), "only the rim: " + cells);
    }

    @Test
    void aPatchOutOfSightIsJudgedOnArrival() {
        ctx.knowledge.note(new PoiMemory(Landmarks.STONE_POI, patch,
                new Region(new Pos(10, G, -2), new Pos(14, G + 1, 2)), 12, false, 0L),
                AgentKnowledge.maxPerKind(ctx.profile()));
        ctx.percepts.blocks.markUnloaded(patch.x(), patch.z());
        assertTrue(forStone().applicable(ctx));
    }

    @Test
    void aFirstBlockThatGaveNothingRestsThePatchForADay() {
        remember();
        MinePatch.Yield yield = new MinePatch.Yield(patch, Stock.FURNACE_STONE, 0);

        assertEquals(TaskStatus.FAILED, yield.tick(ctx), "granite gives granite");
        ctx.percepts.time = MinePatch.BARREN_TICKS - 1;
        assertFalse(forStone().applicable(ctx));
    }

    @Test
    void aFirstBlockThatGaveCobblestoneGoesOn() {
        ctx.percepts.inventory.set(0, ItemStack.of("minecraft:cobblestone", 1, 64));
        assertEquals(TaskStatus.SUCCESS, new MinePatch.Yield(patch, Stock.FURNACE_STONE, 0).tick(ctx));
    }
}
