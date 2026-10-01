package dev.luizloyola.autarkia.core.board;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.luizloyola.anima.core.agent.AgentId;
import dev.luizloyola.anima.core.brain.board.WorkItem;
import dev.luizloyola.anima.core.brain.board.WorkSource;
import dev.luizloyola.anima.core.brain.gate.Act;
import dev.luizloyola.anima.core.brain.gate.Gate;
import dev.luizloyola.anima.core.brain.task.ObtainItem;
import dev.luizloyola.anima.core.inv.Inventory;
import dev.luizloyola.anima.core.inv.ItemStack;
import dev.luizloyola.anima.core.inv.Surplus;
import dev.luizloyola.anima.core.inv.Wear;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** A settler keeps itself in the four tools of its age, and keeps the old ones to use up. */
class ToolUpTest {

    private final BoardBrainContext ctx = new BoardBrainContext();
    private final AgentId me = AgentId.random();
    private final PersonalBoard board = new PersonalBoard();
    private final WorkSource work = board.viewFor(() -> me);
    private final ToolUp pickaxes = new ToolUp(Tools.Family.PICKAXE);
    /** Wear by components string — a stack's durability, as the mod would read it. */
    private final Map<String, Double> wear = new HashMap<>();
    /** The ages this settler has reached, by the tiers they name. */
    private boolean stoneReached;

    ToolUpTest() {
        board.post(pickaxes);
        // The ages name wooden and stone tools; iron is in no node yet, so the gate says yes to it.
        Tools.install(id -> id.startsWith("minecraft:wooden_") || id.startsWith("minecraft:stone_"));
        Gate.install(new Gate.Policy() {
            @Override
            public Optional<String> refuseItem(AgentId body, String itemId) {
                return itemId.startsWith("minecraft:stone_") && !stoneReached
                        ? Optional.of("needs Stone") : Optional.empty();
            }

            @Override
            public Optional<String> refuseAct(AgentId body, Act act) {
                return Optional.empty();
            }
        });
        ctx.gate = Gate.viewFor(me, ctx.journal());
        Wear.install(stack -> stack.id().endsWith("_pickaxe")
                ? OptionalDouble.of(wear.getOrDefault(stack.components(), 1.0))
                : OptionalDouble.empty());
    }

    @AfterEach
    void uninstall() {
        Tools.install(null);
        Gate.install(Gate.OPEN);
        Wear.install(null);
    }

    private void ticks(int n) {
        for (int i = 0; i < n; i++) {
            board.tick(ctx);
            ctx.advance(1);
        }
    }

    private static ItemStack tool(String id) {
        return ItemStack.of(id, 1, 1);
    }

    private ItemStack worn(String id, double left) {
        String components = "worn" + wear.size();
        wear.put(components, left);
        return ItemStack.of(id, 1, 1, components);
    }

    private ObtainItem made(WorkItem item) {
        return (ObtainItem) ((KeepTool) item.root()).methods().get(0).decompose(ctx).get(0);
    }

    @Test
    void aSettlerWithNoPickaxeMakesAWoodenOne() {
        ticks(ToolUp.CHECK_INTERVAL);
        assertTrue(work.bestAvailable(ctx).isEmpty(), "beat one is the warm-up");
        ticks(ToolUp.CHECK_INTERVAL);
        WorkItem item = work.bestAvailable(ctx).orElseThrow();
        assertEquals(ToolUp.LACKING, item.priority());
        assertTrue(made(item).spec().matches("minecraft:wooden_pickaxe"));
        assertEquals(1, made(item).count());
    }

    @Test
    void aSoundToolOfTheAgeIsEnough() {
        ctx.inventory().add(tool("minecraft:wooden_pickaxe"));
        ticks(ToolUp.CHECK_INTERVAL * 3);
        assertTrue(work.bestAvailable(ctx).isEmpty());
    }

    @Test
    void reachingStoneUpgradesAtOnce() {
        ctx.inventory().add(tool("minecraft:wooden_pickaxe"));
        stoneReached = true;
        ticks(ToolUp.CHECK_INTERVAL * 2);
        WorkItem item = work.bestAvailable(ctx).orElseThrow();
        assertEquals(ToolUp.LACKING, item.priority(), "an old tool is not the age's tool");
        assertTrue(made(item).spec().matches("minecraft:stone_pickaxe"));
    }

    @Test
    void aTierNoAgeNamesIsNeverTheAgesTier() {
        stoneReached = true;
        ticks(ToolUp.CHECK_INTERVAL * 2);
        WorkItem item = work.bestAvailable(ctx).orElseThrow();
        assertTrue(made(item).spec().matches("minecraft:stone_pickaxe"),
                "the gate says yes to iron, but no age names it");
    }

    @Test
    void aWornToolAsksForASpareAtTheLowerBid() {
        stoneReached = true;
        ctx.inventory().add(worn("minecraft:stone_pickaxe", 0.1));
        ticks(ToolUp.CHECK_INTERVAL * 2);
        WorkItem item = work.bestAvailable(ctx).orElseThrow();
        assertEquals(ToolUp.SPARE, item.priority());
        assertEquals(2, made(item).count(), "the worn one does not count as the one being made");
    }

    @Test
    void anUnclaimedErrandIsWithdrawnOnceTooledUp() {
        ticks(ToolUp.CHECK_INTERVAL * 2);
        assertTrue(work.bestAvailable(ctx).isPresent());
        ctx.inventory().add(tool("minecraft:wooden_pickaxe"));
        ticks(ToolUp.CHECK_INTERVAL);
        assertTrue(work.bestAvailable(ctx).isEmpty());
    }

    @Test
    void theOldToolsAndTheWornOneAreKeptAndASecondSoundOneIsNot() {
        stoneReached = true;
        Inventory pack = ctx.inventory();
        pack.add(tool("minecraft:wooden_pickaxe"));
        pack.add(tool("minecraft:golden_pickaxe"));
        pack.add(worn("minecraft:stone_pickaxe", 0.1));
        pack.add(tool("minecraft:stone_pickaxe"));
        pack.add(tool("minecraft:iron_pickaxe"));
        ticks(1);
        List<Integer> cargo = Surplus.slots(pack, pickaxes.reserved(), stack -> false);
        assertEquals(1, cargo.size(), "one sound tool and the worn one at the tier, every old one");
        String spare = pack.get(cargo.get(0)).id();
        assertTrue(Set.of("minecraft:stone_pickaxe", "minecraft:iron_pickaxe").contains(spare));
    }

    @Test
    void aClaimedErrandSurvivesARestart() {
        ticks(ToolUp.CHECK_INTERVAL * 2);
        WorkItem item = work.bestAvailable(ctx).orElseThrow();
        work.claimed(item, ctx);
        List<dev.luizloyola.autarkia.core.board.KeepStocked.State> saved = board.snapshot();

        PersonalBoard reloaded = new PersonalBoard();
        ToolUp fresh = new ToolUp(Tools.Family.PICKAXE);
        reloaded.post(fresh);
        Optional<WorkItem> held = reloaded.restore(saved, me, 0);
        assertTrue(held.isPresent(), "the arbiter is pointed back at the same errand");
        assertEquals(board.snapshot(), reloaded.snapshot());
    }
}
