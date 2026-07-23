package dev.luizloyola.autarkia.core.brain.task;

import dev.luizloyola.autarkia.core.brain.BrainContext;
import dev.luizloyola.autarkia.core.brain.act.ActuatorAccess;
import dev.luizloyola.autarkia.core.brain.act.ItemConsumer;
import dev.luizloyola.autarkia.core.brain.act.Mover;
import dev.luizloyola.autarkia.core.brain.sense.Percepts;
import dev.luizloyola.autarkia.core.log.JournalService;
import dev.luizloyola.autarkia.core.log.PersonJournal;
import dev.luizloyola.autarkia.core.person.PersonId;

/**
 * The test {@link BrainContext}: everything a task or method can reach, scripted and inspectable.
 * Mirrors what the mod {@code BrainDriver} assembles over a live Person, minus Minecraft.
 */
final class FakeContext implements BrainContext {
    final FakeMover mover = new FakeMover();
    final FakeConsumer consumer = new FakeConsumer();
    final FakePercepts percepts = new FakePercepts();
    /**
     * A real (in-memory) journal on a fixed-tick clock, so a narrating task records somewhere
     * inspectable ({@code journalService.recent(...)}). Bound to one throwaway person.
     */
    final JournalService journalService = new JournalService(() -> 0L);
    private final PersonJournal journal = journalService.forPerson(PersonId.random());
    /**
     * The cost ceiling the executor gates methods by (raw food is priced out at 60, admitted at
     * ∞). Defaults to ∞, so tests predating cost tolerance see every applicable method.
     */
    double costTolerance = Double.POSITIVE_INFINITY;
    private final ActuatorAccess actuators = new ActuatorAccess() {
        @Override
        public Mover mover() {
            return mover;
        }

        @Override
        public ItemConsumer consumer() {
            return consumer;
        }
    };

    @Override
    public ActuatorAccess actuators() {
        return actuators;
    }

    @Override
    public Percepts percepts() {
        return percepts;
    }

    @Override
    public PersonJournal journal() {
        return journal;
    }

    @Override
    public double costTolerance() {
        return costTolerance;
    }
}
