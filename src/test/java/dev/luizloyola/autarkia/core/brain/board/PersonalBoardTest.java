package dev.luizloyola.autarkia.core.brain.board;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.luizloyola.autarkia.core.brain.BrainContext;
import dev.luizloyola.autarkia.core.brain.act.ActuatorAccess;
import dev.luizloyola.autarkia.core.brain.knowledge.PersonKnowledge;
import dev.luizloyola.autarkia.core.brain.sense.Percepts;
import dev.luizloyola.autarkia.core.inv.ItemSpec;
import dev.luizloyola.autarkia.core.inv.ItemStack;
import org.junit.jupiter.api.Test;

/** The placeholder demand generator: cadence posting, withdrawal, completion, retry pacing. */
class PersonalBoardTest {

    private final BoardContext ctx = new BoardContext();
    private final PersonalBoard board = new PersonalBoard(ItemSpec.LOGS, 16, 0.35, 0);

    private void ticks(int n) {
        for (int i = 0; i < n; i++) {
            board.tick(ctx);
        }
    }

    @Test
    void postsOnItsSecondBeatWhenShort() {
        ticks(PersonalBoard.CHECK_INTERVAL);
        assertTrue(board.bestAvailable(ctx).isEmpty(),
                "beat one is the warm-up: they look before they want");
        ticks(PersonalBoard.CHECK_INTERVAL);
        assertTrue(board.bestAvailable(ctx).isPresent(), "short on logs -> posted");
    }

    @Test
    void withdrawsAnUnclaimedItemThatBecameMoot() {
        ticks(PersonalBoard.CHECK_INTERVAL * 2);
        assertTrue(board.bestAvailable(ctx).isPresent());
        ctx.inventory().add(ItemStack.of("minecraft:oak_log", 16, 64));
        ticks(PersonalBoard.CHECK_INTERVAL);
        assertTrue(board.bestAvailable(ctx).isEmpty(), "stocked by luck -> withdrawn");
    }

    @Test
    void claimHidesTheItemAndCompletionClosesIt() {
        ticks(PersonalBoard.CHECK_INTERVAL * 2);
        WorkItem item = board.bestAvailable(ctx).orElseThrow();
        board.claimed(item, ctx);
        assertTrue(board.bestAvailable(ctx).isEmpty(), "claimed items are not on offer");
        ctx.inventory().add(ItemStack.of("minecraft:oak_log", 16, 64));
        board.completed(item, ctx);
        ticks(PersonalBoard.CHECK_INTERVAL);
        assertTrue(board.bestAvailable(ctx).isEmpty(), "stocked -> nothing re-posted");
    }

    @Test
    void failurePacesTheRetry() {
        ticks(PersonalBoard.CHECK_INTERVAL * 2);
        WorkItem item = board.bestAvailable(ctx).orElseThrow();
        board.claimed(item, ctx);
        board.failed(item, ctx);
        ticks(PersonalBoard.FAIL_COOLDOWN - PersonalBoard.CHECK_INTERVAL);
        assertFalse(board.bestAvailable(ctx).isPresent(), "cooling: the want waits");
        ticks(PersonalBoard.FAIL_COOLDOWN);
        assertTrue(board.bestAvailable(ctx).isPresent(), "cooldown over -> posted again");
    }

    /** A minimal context for the board: real inventory + journal, nothing else consulted. */
    private static final class BoardContext implements BrainContext {
        private final dev.luizloyola.autarkia.core.inv.Inventory inventory =
                new dev.luizloyola.autarkia.core.inv.Inventory();
        private final dev.luizloyola.autarkia.core.log.JournalService journal =
                new dev.luizloyola.autarkia.core.log.JournalService(() -> 0L);
        private final dev.luizloyola.autarkia.core.log.PersonJournal view =
                journal.forPerson(dev.luizloyola.autarkia.core.person.PersonId.random());
        dev.luizloyola.autarkia.core.inv.Inventory inventory() {
            return inventory;
        }

        @Override
        public ActuatorAccess actuators() {
            throw new UnsupportedOperationException("the board never acts");
        }

        @Override
        public dev.luizloyola.autarkia.core.person.Gender gender() {
            return dev.luizloyola.autarkia.core.person.Gender.FEMALE;
        }

        @Override
        public Percepts percepts() {
            // The board reads exactly one sense: what the pack holds.
            return new Percepts() {
                @Override
                public dev.luizloyola.autarkia.core.inv.Inventory inventory() {
                    return inventory;
                }

                @Override
                public dev.luizloyola.autarkia.core.person.Needs needs() {
                    throw new UnsupportedOperationException();
                }

                @Override
                public dev.luizloyola.autarkia.core.brain.sense.FoodLookup foods() {
                    throw new UnsupportedOperationException();
                }

                @Override
                public dev.luizloyola.autarkia.core.brain.sense.Pos position() {
                    throw new UnsupportedOperationException();
                }

                @Override
                public java.util.List<dev.luizloyola.autarkia.core.brain.sense.Threat> threats() {
                    throw new UnsupportedOperationException();
                }

                @Override
                public dev.luizloyola.autarkia.core.brain.knowledge.BlockProbe blocks() {
                    throw new UnsupportedOperationException();
                }

                @Override
                public java.util.List<dev.luizloyola.autarkia.core.brain.sense.Drop> drops() {
                    throw new UnsupportedOperationException();
                }

                @Override
                public java.util.List<dev.luizloyola.autarkia.core.brain.sense.Peer> peers() {
                    throw new UnsupportedOperationException();
                }

                @Override
                public long time() {
                    return 0L;
                }
            };
        }

        @Override
        public dev.luizloyola.autarkia.core.log.PersonJournal journal() {
            return view;
        }

        @Override
        public PersonKnowledge knowledge() {
            throw new UnsupportedOperationException("the board never reads memories");
        }

        @Override
        public double costTolerance() {
            return Double.POSITIVE_INFINITY;
        }
    }
}
