package dev.luizloyola.autarkia.core.board;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.luizloyola.anima.core.brain.BrainContext;
import dev.luizloyola.anima.core.brain.knowledge.AgentKnowledge;
import dev.luizloyola.anima.core.brain.knowledge.Sighting;
import dev.luizloyola.anima.core.brain.sense.Pos;
import dev.luizloyola.anima.core.brain.task.FakeContext;
import dev.luizloyola.anima.core.brain.task.GoTo;
import dev.luizloyola.anima.core.brain.task.LookRound;
import dev.luizloyola.anima.core.brain.task.Method;
import dev.luizloyola.anima.core.brain.task.Producers;
import dev.luizloyola.anima.core.brain.task.Task;
import dev.luizloyola.anima.core.inv.ItemSpec;
import dev.luizloyola.autarkia.core.tree.Pois;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class SeekSourceTest {

    /** Wood only a body standing east of x = 100 knows of. */
    private static final ItemSpec FAR_WOOD = ItemSpec.register(
            new ItemSpec("seek-source-test-wood", id -> id.startsWith("test:far_wood")));

    static {
        Producers.register(FAR_WOOD, id -> true, wanted -> new Method() {
            @Override
            public boolean applicable(BrainContext ctx) {
                return ctx.percepts().position().x() > 100;
            }

            @Override
            public double estimateCost(BrainContext ctx) {
                return 30;
            }

            @Override
            public List<Task> decompose(BrainContext ctx) {
                return List.of();
            }

            @Override
            public String describe() {
                return "chop the far wood";
            }
        });
    }

    private final FakeContext ctx = new FakeContext();

    private SeekSource east() {
        return new SeekSource(Pois.TREE, FAR_WOOD, Set.of(), 0, 4);
    }

    /** The cheapest applicable way, as the executor would pick it. */
    private Method chosen(SeekSource seek) {
        Method best = null;
        for (Method way : seek.methods()) {
            if (way.applicable(ctx) && (best == null || way.estimateCost(ctx) < best.estimateCost(ctx))) {
                best = way;
            }
        }
        return best;
    }

    @Test
    void itLooksRoundThenWalksOnAlongItsHeading() {
        ctx.percepts.position = new Pos(0, 64, 0);
        SeekSource seek = east();
        assertFalse(seek.satisfied(ctx));

        assertInstanceOf(LookRound.class, chosen(seek).decompose(ctx).get(0), "a look before a step");
        GoTo leg = assertInstanceOf(GoTo.class, chosen(seek).decompose(ctx).get(0));
        assertEquals(3, seek.legsLeft());
        assertTrue(leg.describe().contains("64"), "one leg east: " + leg.describe());
    }

    @Test
    void aGlimpseOfWhatItWantsComesFirst() {
        ctx.percepts.position = new Pos(0, 64, 0);
        Pos trees = new Pos(40, 64, 90);
        ctx.knowledge.glimpse(new Sighting(Pois.TREE, trees, new Pos(0, 64, 0), 0, Sighting.Provenance.SURVEY),
                AgentKnowledge.maxPerKind(ctx.profile()));
        SeekSource seek = east();

        GoTo there = assertInstanceOf(GoTo.class, chosen(seek).decompose(ctx).get(0));
        assertTrue(there.describe().contains("40") && there.describe().contains("90"), there.describe());
        assertEquals(List.of(trees), seek.visited(), "one that leads nowhere is not walked to again");
    }

    @Test
    void itIsDoneOnceItKnowsAWay() {
        ctx.percepts.position = new Pos(150, 64, 0);
        assertTrue(east().satisfied(ctx));
    }

    @Test
    void headingsRunEastFirstAndClockwise() {
        assertEquals(0, SeekSource.headingTo(new Pos(0, 64, 0), new Pos(100, 64, 0)));
        assertEquals(2, SeekSource.headingTo(new Pos(0, 64, 0), new Pos(0, 64, 100)), "south is +z");
        assertEquals(4, SeekSource.headingTo(new Pos(0, 64, 0), new Pos(-100, 64, 0)));
    }
}
