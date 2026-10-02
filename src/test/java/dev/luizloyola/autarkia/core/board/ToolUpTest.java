package dev.luizloyola.autarkia.core.board;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.luizloyola.anima.core.agent.AgentId;
import dev.luizloyola.anima.core.brain.board.WorkItem;
import dev.luizloyola.anima.core.brain.board.WorkSource;
import dev.luizloyola.anima.core.brain.gate.Act;
import dev.luizloyola.anima.core.brain.gate.Gate;
import dev.luizloyola.anima.core.brain.task.AtOneBench;
import dev.luizloyola.anima.core.brain.task.ObtainItem;
import dev.luizloyola.anima.core.brain.task.Task;
import dev.luizloyola.anima.core.brain.task.TaskExecutor;
import dev.luizloyola.anima.core.brain.task.TaskStatus;
import dev.luizloyola.anima.core.craft.CraftRecipe;
import dev.luizloyola.anima.core.craft.Recipes;
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
        Recipes.reset();
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

    /** A settler's four, on a board of their own, ticked to the first beat that posts. */
    private PersonalBoard allFourPosted() {
        PersonalBoard four = new PersonalBoard();
        ToolUp.settlerDefaults().forEach(four::post);
        for (int i = 0; i < ToolUp.CHECK_INTERVAL * 2; i++) {
            four.tick(ctx);
            ctx.advance(1);
        }
        return four;
    }

    private static List<Tools.Family> families(Task run) {
        return ((AtOneBench) run).work().stream().map(task -> ((KeepTool) task).family()).toList();
    }

    @Test
    void everyFamilyLackingIsMadeInTheOneRun() {
        WorkSource four = allFourPosted().viewFor(() -> me);
        WorkItem item = four.bestAvailable(ctx).orElseThrow();
        four.claimed(item, ctx);

        List<Tools.Family> run = families(item.root());
        assertEquals(Set.of(Tools.Family.values()), Set.copyOf(run));
        assertEquals(4, run.size());
        assertTrue(item.describe().endsWith(run.get(0).tool()), "the claimed family leads");
    }

    @Test
    void aToolThatCannotBeMadeDoesNotHoldUpTheOthers() {
        // In-hand, so the run needs no world: what is under test is who gets made.
        List<CraftRecipe> book = java.util.stream.Stream.of("pickaxe", "axe", "shovel")
                .map(tool -> new CraftRecipe("minecraft:wooden_" + tool,
                        ItemStack.of("minecraft:wooden_" + tool, 1, 1),
                        List.of(new CraftRecipe.Ingredient(Set.of("minecraft:oak_planks"), 3),
                                new CraftRecipe.Ingredient(Set.of("minecraft:stick"), 2)), false))
                .toList();
        Recipes.provide(spec -> book.stream().filter(r -> spec.matches(r.outputId())).toList());
        ctx.inventory().add(ItemStack.of("minecraft:oak_planks", 9, 64));
        ctx.inventory().add(ItemStack.of("minecraft:stick", 6, 64));
        PersonalBoard own = allFourPosted();
        WorkSource four = own.viewFor(() -> me);
        WorkItem item = four.bestAvailable(ctx).orElseThrow();
        four.claimed(item, ctx);
        assertTrue(item.describe().endsWith("sword"), "the one with no recipe is claimed first");

        TaskExecutor executor = new TaskExecutor();
        executor.run(item.root(), ctx);
        for (int tick = 0; tick < 200 && executor.isBusy(); tick++) {
            ctx.advance(1);
            executor.tick(ctx);
        }
        assertEquals(Optional.of(TaskStatus.SUCCESS), executor.lastStatus());
        for (String tool : List.of("pickaxe", "axe", "shovel")) {
            assertEquals(1, ctx.inventory().count("minecraft:wooden_" + tool), tool);
        }

        four.completed(item, ctx);
        assertTrue(four.bestAvailable(ctx).isEmpty(), "the three made are not offered again");
        for (int i = 0; i < ToolUp.CHECK_INTERVAL; i++) {
            own.tick(ctx);
            ctx.advance(1);
        }
        assertTrue(four.bestAvailable(ctx).isEmpty(), "the sword sits out its cooldown");
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

    // ── a tool priced out ────────────────────────────────────────────────────────────────────

    /** Ticks to the next beat that offers the pickaxe, and claims it. */
    private WorkItem nextClaimed() {
        for (int i = 0; i < 10_000; i++) {
            Optional<WorkItem> offer = work.bestAvailable(ctx);
            if (offer.isPresent()) {
                work.claimed(offer.get(), ctx);
                return offer.get();
            }
            ticks(1);
        }
        throw new AssertionError("never offered again");
    }

    /** The arbiter's side of a run whose one way costs {@code price}: made if the budget reaches it. */
    private double attempt(double price) {
        WorkItem item = nextClaimed();
        double budget = dev.luizloyola.anima.core.brain.WorkToleranceCurve.tolerance(item.priority(),
                work.budgetSteps(item));
        if (budget < price) {
            work.pricedOut(item, ctx);
            work.failed(item, ctx);
        } else {
            ctx.inventory().add(tool("minecraft:wooden_pickaxe"));
            work.completed(item, ctx);
        }
        return budget;
    }

    private boolean journalled(String detail) {
        return ctx.journal().recent(Integer.MAX_VALUE).stream().anyMatch(e -> e.detail().equals(detail));
    }

    @Test
    void aToolPricedOutEarnsBudgetUntilItIsMade() {
        // Stone 120 walk-blocks off: past a lacking tool's 77, under the curve's cap.
        List<Double> budgets = new java.util.ArrayList<>();
        while (ctx.inventory().count("minecraft:wooden_pickaxe") == 0) {
            budgets.add(attempt(120));
            assertTrue(budgets.size() < 10, "made before the budget stops growing");
        }
        assertEquals(4, budgets.size(), "77, 93, 109, then 125 affords it: " + budgets);
        for (int i = 1; i < budgets.size(); i++) {
            assertTrue(budgets.get(i) > budgets.get(i - 1), "the budget grows: " + budgets);
        }
        assertTrue(journalled("priced out — its budget grows to 93 blocks"));
        KeepStocked.State made = pickaxes.snapshot();
        assertEquals(0, made.steps(), "a tool made ends the steps");
        assertEquals(0, made.failures(), "and the failures in a row");
    }

    @Test
    void theRepostBacksOffAndResetsOnceTooledUp() {
        List<Integer> waits = new java.util.ArrayList<>();
        for (int i = 0; i < 5; i++) {
            WorkItem item = nextClaimed();
            work.failed(item, ctx);
            waits.add(pickaxes.snapshot().cooldown());
        }
        assertEquals(List.of(600, 1200, 2400, 4800, 4800), waits);
        assertTrue(journalled("unclaimed, retry cooldown (4800t)"));

        ticks(4800);
        ctx.inventory().add(tool("minecraft:wooden_pickaxe"));
        ticks(ToolUp.CHECK_INTERVAL);
        ctx.inventory().clear();
        work.failed(nextClaimed(), ctx);
        assertEquals(600, pickaxes.snapshot().cooldown(), "a tool had starts the wait over");
    }

    @Test
    void anAgeReachedStartsTheNewToolAfresh() {
        attempt(120);
        attempt(120);
        assertEquals(2, pickaxes.snapshot().steps());
        stoneReached = true;
        ticks(ToolUp.CHECK_INTERVAL);
        KeepStocked.State now = pickaxes.snapshot();
        assertEquals(List.of(0, 0, 0), List.of(now.steps(), now.failures(), now.cooldown()),
                "a stone pickaxe's price is not a wooden one's");
    }

    @Test
    void aRestartKeepsTheBudgetAndTheBackOff() {
        attempt(140);
        attempt(140);
        List<KeepStocked.State> saved = board.snapshot();
        assertEquals(2, saved.get(0).steps());
        assertEquals(2, saved.get(0).failures());

        PersonalBoard reloaded = new PersonalBoard();
        ToolUp fresh = new ToolUp(Tools.Family.PICKAXE);
        reloaded.post(fresh);
        reloaded.restore(saved, me, ctx.now());
        assertEquals(saved, reloaded.snapshot());
        WorkSource again = reloaded.viewFor(() -> me);
        WorkItem item = null;
        for (int i = 0; i < 10_000 && item == null; i++) {
            item = again.bestAvailable(ctx).orElse(null);
            reloaded.tick(ctx);
            ctx.advance(1);
        }
        assertEquals(2, again.budgetSteps(item), "the budget earned before the restart");
        again.claimed(item, ctx);
        again.failed(item, ctx);
        assertEquals(2400, fresh.snapshot().cooldown(), "the third failure in a row");
    }
}
