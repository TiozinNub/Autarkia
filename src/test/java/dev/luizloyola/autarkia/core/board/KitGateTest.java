package dev.luizloyola.autarkia.core.board;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.luizloyola.anima.core.agent.AgentId;
import dev.luizloyola.anima.core.brain.BrainContext;
import dev.luizloyola.anima.core.brain.board.WorkItem;
import dev.luizloyola.anima.core.brain.task.Task;
import dev.luizloyola.anima.core.inv.ItemCall;
import dev.luizloyola.anima.core.inv.ItemSpec;
import dev.luizloyola.anima.core.inv.ItemStack;
import dev.luizloyola.anima.core.inv.Kit;
import dev.luizloyola.anima.core.log.Entry;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The kit gate at the offer path, flipped: a coverable missing NEED no longer hides an item (the
 * claim goes through and the {@code KittedErrand} fetches under the lease) — what still declines
 * is a need this asker has no WAY to get: not in pack, no producer, nothing craftable in hand.
 * WANTs gate nothing at all, and the decline stays explicit (a paced {@code passed over} line) and
 * costs the project no cooldown.
 */
class KitGateTest {

    private static final ItemSpec PICKAXES =
            ItemSpec.register(new ItemSpec("gate-test-pickaxes", id -> id.endsWith("_pickaxe")));
    private static final ItemSpec AXES =
            ItemSpec.register(new ItemSpec("gate-test-axes", id -> id.endsWith("_axe")));

    private final BoardBrainContext ctx = new BoardBrainContext();
    private final Board board = new Board();
    private final AgentId asker = AgentId.random();

    @org.junit.jupiter.api.AfterEach
    void tearDown() {
        dev.luizloyola.anima.core.craft.Recipes.reset();
    }

    private static ItemStack pickaxe() {
        return ItemStack.of("minecraft:wooden_pickaxe", 1, 1);
    }

    @Test
    void anUncoverableNeedHidesTheItemUntilThePackCoversIt() {
        // No producer, no recipe: a pickaxe this asker has no way to get.
        KitProject project = new KitProject();
        project.add(new KittedItem("mine stone", Kit.of(ItemCall.need(PICKAXES, 1))));
        board.post(project);

        assertTrue(board.bestFor(asker, ctx, ctx.now()).isEmpty(),
                "no pickaxe and no way to make one -> the item is not for this asker");
        ctx.inventory().add(pickaxe());
        assertTrue(board.bestFor(asker, ctx, ctx.now()).isPresent(),
                "the pack now covers the need -> on offer again");
    }

    @Test
    void aCraftableNeedClaimsThroughAndFetchesUnderTheLease() {
        dev.luizloyola.anima.core.craft.Recipes.provide(spec ->
                spec.matches("minecraft:wooden_pickaxe")
                        ? java.util.List.of(inHandPickaxeRecipe())
                        : java.util.List.of());
        KitProject project = new KitProject();
        WorkItem mine = project.add(new KittedItem("mine stone", Kit.of(ItemCall.need(PICKAXES, 1))));
        board.post(project);

        assertSame(mine, board.bestFor(asker, ctx, ctx.now()).orElseThrow(),
                "an empty pack is no bar when the need is craftable — the errand kits itself");
    }

    @Test
    void aNeedWhoseOnlyRecipeWantsATableStillDeclines() {
        // Until the table era: an errand cannot reach a workbench, so a table-only recipe is
        // not a way to cover a need yet. This assertion FLIPS when EnsureTable lands.
        dev.luizloyola.anima.core.craft.Recipes.provide(spec ->
                spec.matches("minecraft:wooden_pickaxe")
                        ? java.util.List.of(tablePickaxeRecipe())
                        : java.util.List.of());
        KitProject project = new KitProject();
        project.add(new KittedItem("mine stone", Kit.of(ItemCall.need(PICKAXES, 1))));
        board.post(project);

        assertTrue(board.bestFor(asker, ctx, ctx.now()).isEmpty());
    }

    private static dev.luizloyola.anima.core.craft.CraftRecipe inHandPickaxeRecipe() {
        return new dev.luizloyola.anima.core.craft.CraftRecipe("test:pickaxe",
                dev.luizloyola.anima.core.inv.ItemStack.of("minecraft:wooden_pickaxe", 1, 1),
                java.util.List.of(new dev.luizloyola.anima.core.craft.CraftRecipe.Ingredient(
                        java.util.Set.of("minecraft:oak_planks"), 3)),
                false);
    }

    private static dev.luizloyola.anima.core.craft.CraftRecipe tablePickaxeRecipe() {
        return new dev.luizloyola.anima.core.craft.CraftRecipe("test:pickaxe-table",
                dev.luizloyola.anima.core.inv.ItemStack.of("minecraft:wooden_pickaxe", 1, 1),
                java.util.List.of(new dev.luizloyola.anima.core.craft.CraftRecipe.Ingredient(
                        java.util.Set.of("minecraft:oak_planks"), 3)),
                true);
    }

    @Test
    void aWantGatesNothing() {
        KitProject project = new KitProject();
        WorkItem chop = project.add(new KittedItem("chop", Kit.of(ItemCall.want(AXES, 1))));
        board.post(project);

        assertSame(chop, board.bestFor(asker, ctx, ctx.now()).orElseThrow(),
                "an empty-handed chopper still chops — a want improves work, never blocks it");
    }

    @Test
    void aBlockedItemLosesToAnOpenOneRegardlessOfScore() {
        KitProject project = new KitProject();
        project.add(new KittedItem("mine stone", Kit.of(ItemCall.need(PICKAXES, 1))));
        WorkItem bare = project.add(new KittedItem("carry water", Kit.NONE));
        board.post(project);

        assertSame(bare, board.bestFor(asker, ctx, ctx.now()).orElseThrow(),
                "the kitless work is what this body can actually do");
    }

    @Test
    void theDeclineIsJournaledOnceAndPaced() {
        KitProject project = new KitProject();
        project.add(new KittedItem("mine stone", Kit.of(ItemCall.need(PICKAXES, 1))));
        board.post(project);

        board.bestFor(asker, ctx, ctx.now());
        board.bestFor(asker, ctx, ctx.now()); // the very next scan: same fact, no second line
        List<Entry> lines = ctx.journal().recent(10);
        assertEquals(1, passedOverLines(lines),
                "one line, not one per offer scan: " + lines);
        assertTrue(lines.get(0).detail().contains("no way to get gate-test-pickaxes"),
                "the line names what is missing: " + lines.get(0).detail());

        ctx.advance(Board.PASS_OVER_LOG_INTERVAL + 1);
        board.bestFor(asker, ctx, ctx.now());
        assertEquals(2, passedOverLines(ctx.journal().recent(10)),
                "after the pace interval the standing fact is worth restating");
    }

    private static long passedOverLines(List<Entry> lines) {
        return lines.stream().filter(entry -> entry.detail().startsWith("passed over")).count();
    }

    /** A project whose items sit on offer — the gate is the board's, not the project's. */
    private static final class KitProject implements Project {
        private final List<WorkItem> items = new ArrayList<>();

        WorkItem add(WorkItem item) {
            items.add(item);
            return item;
        }

        @Override
        public double priority() {
            return 0.5;
        }

        @Override
        public List<WorkItem> open() {
            return List.copyOf(items);
        }

        @Override
        public boolean finished() {
            return false;
        }

        @Override
        public void completed(WorkItem item, BrainContext ctx) {
        }

        @Override
        public void failed(WorkItem item, BrainContext ctx) {
        }

        @Override
        public String describe() {
            return "kitted errands";
        }
    }

    /** An item that calls for a kit and never runs — these tests end at the offer. */
    private record KittedItem(String name, Kit kit) implements WorkItem {
        @Override
        public double priority() {
            return 0.5;
        }

        @Override
        public Task root() {
            throw new UnsupportedOperationException("no test here runs the work");
        }

        @Override
        public String describe() {
            return name;
        }
    }
}
