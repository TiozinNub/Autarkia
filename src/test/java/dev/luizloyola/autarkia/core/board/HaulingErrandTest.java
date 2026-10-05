package dev.luizloyola.autarkia.core.board;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.luizloyola.anima.core.brain.knowledge.Coverage;
import dev.luizloyola.anima.core.brain.task.CompoundTask;
import dev.luizloyola.anima.core.brain.task.Idle;
import dev.luizloyola.anima.core.brain.task.Method;
import dev.luizloyola.anima.core.brain.task.SweepingErrand;
import java.util.List;
import org.junit.jupiter.api.Test;

class HaulingErrandTest {

    private static final CompoundTask FELL = new CompoundTask() {
        @Override
        public List<Method> methods() {
            return List.of();
        }

        @Override
        public String describe() {
            return "fell";
        }

        @Override
        public boolean reshapesGround() {
            return true;
        }
    };

    @Test
    void aClearErrandReshapesGroundBeforeItExpands() {
        SweepingErrand errand = new SweepingErrand(
                new HaulingErrand(FELL, 64, () -> true), Coverage.NONE);

        assertTrue(errand.reshapesGround(),
                "the escape drive must not take a body on its own pillar for stuck");
    }

    @Test
    void aHaulAroundOrdinaryWorkDoesNot() {
        assertFalse(new HaulingErrand(new Idle(1), 64, () -> true).reshapesGround());
    }
}
