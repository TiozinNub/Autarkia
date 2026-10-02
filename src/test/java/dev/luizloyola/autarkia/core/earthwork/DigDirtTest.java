package dev.luizloyola.autarkia.core.earthwork;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.luizloyola.anima.core.agent.AgentId;
import dev.luizloyola.anima.core.brain.gate.Act;
import dev.luizloyola.anima.core.brain.gate.Gate;
import dev.luizloyola.anima.core.brain.knowledge.BlockKind;
import dev.luizloyola.anima.core.brain.knowledge.FakeProbe;
import dev.luizloyola.anima.core.brain.sense.Pos;
import dev.luizloyola.anima.core.brain.task.FakeContext;
import dev.luizloyola.anima.core.brain.task.Task;
import dev.luizloyola.anima.core.brain.task.TaskStatus;
import dev.luizloyola.anima.core.brain.task.Try;
import dev.luizloyola.anima.core.inv.ItemStack;
import dev.luizloyola.anima.core.nav.HandsOff;
import dev.luizloyola.autarkia.core.board.Stock;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Dirt from the ground: the nearest scrape outside the fence, never a pit, never beside water. */
class DigDirtTest {

    private static final int G = FakeProbe.GROUND_Y;

    private final FakeContext ctx = new FakeContext();
    private final FakeProbe probe = ctx.percepts.blocks;
    private final Pos here = new Pos(0, G + 1, 0);

    @BeforeEach
    void setUp() {
        ctx.percepts.position = here;
        Stock.dirtBy(id -> id.equals("minecraft:dirt") || id.equals("minecraft:grass_block"));
    }

    @AfterEach
    void tearDown() {
        Gate.install(Gate.OPEN);
        DigDirt.fenceBy((near, reach) -> HandsOff.NONE);
        Stock.dirtBy(id -> false);
    }

    /** Grass on the column's top at {@code height}, ground built up under it. */
    private void grass(int x, int z, int height) {
        for (int y = G + 1; y <= height; y++) {
            probe.set(x, y, z, BlockKind.OTHER);
        }
        probe.setId(x, height, z, "minecraft:grass_block");
    }

    /** A grass column two above the flat ground round it. */
    private void mound(int x, int z) {
        grass(x, z, G + 2);
    }

    private static List<Pos> scrape(FakeProbe probe, HandsOff fence, Pos here) {
        return DigDirt.scrape(probe, fence, here);
    }

    @Test
    void aMoundThatIsNotDirtIsNoWay() {
        probe.set(6, G + 1, 0, BlockKind.OTHER);
        probe.set(6, G + 2, 0, BlockKind.OTHER);
        assertFalse(new DigDirt(Stock.DIRT).applicable(ctx));
    }

    @Test
    void flatGrassIsNeverDug() {
        for (int x = -6; x <= 6; x++) {
            for (int z = -6; z <= 6; z++) {
                grass(x, z, G);
            }
        }
        assertFalse(new DigDirt(Stock.DIRT).applicable(ctx), "nothing stands above the ground round it");
        assertEquals(List.of(), scrape(probe, HandsOff.NONE, here));
    }

    @Test
    void theNearestMoundIsDugPricedByTheWalk() {
        mound(6, 0);
        mound(20, 0);
        DigDirt dig = new DigDirt(Stock.DIRT);
        assertTrue(dig.applicable(ctx));
        assertEquals(6 + DigDirt.WORK, dig.estimateCost(ctx), 1e-9);
        assertEquals(List.of(new Pos(6, G + 2, 0)), scrape(probe, HandsOff.NONE, here));
    }

    @Test
    void aMoundIsDugDownToTheGroundRoundItAndNoFurther() {
        mound(6, 0);
        assertEquals(List.of(new Pos(6, G + 2, 0)), scrape(probe, HandsOff.NONE, here));
        probe.clear(6, G + 2, 0);
        probe.setId(6, G + 1, 0, "minecraft:dirt");
        assertEquals(List.of(), scrape(probe, HandsOff.NONE, here),
                "one above flat ground is within the flatten's deadband: left");
    }

    @Test
    void aHolesRimIsCutAndItsFloorIsNot() {
        // A plateau one up round (10, 0), a hole to the old ground at its middle, a rim two up round it.
        for (int x = 5; x <= 15; x++) {
            for (int z = -5; z <= 5; z++) {
                int dx = Math.abs(x - 10);
                int dz = Math.abs(z);
                grass(x, z, dx == 0 && dz == 0 ? G : Math.max(dx, dz) == 1 ? G + 3 : G + 1);
            }
        }
        List<Pos> cells = scrape(probe, HandsOff.NONE, here);
        assertFalse(cells.isEmpty());
        assertTrue(cells.stream().allMatch(c -> c.y() == G + 3), "only the rim: " + cells);
        assertFalse(cells.contains(new Pos(10, G, 0)), "never the floor");
    }

    @Test
    void fencedGroundAndItsMarginAreLeftWhole() {
        mound(4, 0);
        mound(10 + DigDirt.MARGIN, 0);
        mound(10 + DigDirt.MARGIN + 1, 0);
        HandsOff fence = HandsOff.columns(List.of(new int[] {-10, -10, 10, 10}));
        assertEquals(List.of(new Pos(10 + DigDirt.MARGIN + 1, G + 2, 0)), scrape(probe, fence, here),
                "not inside, and not within the margin of it");
    }

    @Test
    void dirtBesideWaterIsLeft() {
        mound(5, 0);
        probe.set(5, G + 2, 1, BlockKind.WATER);
        assertEquals(List.of(), scrape(probe, HandsOff.NONE, here));
    }

    @Test
    void oneTripTakesAPatchRoundTheNearest() {
        for (int x = 5; x <= 15; x++) {
            for (int z = -5; z <= 5; z++) {
                if ((x + z) % 2 == 0) {
                    grass(x, z, G + 3);
                }
            }
        }
        List<Pos> cells = scrape(probe, HandsOff.NONE, here);
        assertEquals(DigDirt.MOST, cells.size());
        assertEquals(5, cells.get(0).x());
        assertTrue(cells.stream().allMatch(c -> c.x() <= 5 + 2 * DigDirt.SPREAD && Math.abs(c.z()) <= 2 * DigDirt.SPREAD));
    }

    @Test
    void theTripDigsGathersAndChecksWhatItGot() {
        mound(6, 0);
        List<Task> plan = new DigDirt(Stock.DIRT).decompose(ctx);
        assertEquals(4, plan.size());
        assertInstanceOf(Try.class, plan.get(1));
        DigDirt.Dug dug = assertInstanceOf(DigDirt.Dug.class, plan.get(3));

        assertEquals(TaskStatus.FAILED, dug.tick(ctx), "nothing came of it");
        ctx.percepts.inventory.add(ItemStack.of("minecraft:dirt", 1, 64));
        assertEquals(TaskStatus.SUCCESS, dug.tick(ctx));
    }

    @Test
    void aBodyTheGateKeepsFromDiggingDoesNot() {
        mound(6, 0);
        Gate.install(new Gate.Policy() {
            @Override
            public Optional<String> refuseItem(AgentId body, String itemId) {
                return Optional.empty();
            }

            @Override
            public Optional<String> refuseAct(AgentId body, Act act) {
                return act.equals(DigDirt.ACT) ? Optional.of("not yet") : Optional.empty();
            }
        });
        ctx.gate = Gate.viewFor(ctx.self, ctx.journal());
        assertFalse(new DigDirt(Stock.DIRT).applicable(ctx));
    }

    @Test
    void theFenceTheModInstallsIsAsked() {
        mound(6, 0);
        DigDirt.fenceBy((near, reach) -> HandsOff.columns(List.of(new int[] {-20, -20, 20, 20})));
        assertFalse(new DigDirt(Stock.DIRT).applicable(ctx));
    }
}
