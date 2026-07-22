package dev.luizloyola.autarkia.mod.brain;

import dev.luizloyola.autarkia.core.brain.BrainContext;
import dev.luizloyola.autarkia.core.brain.act.ActuatorAccess;
import dev.luizloyola.autarkia.core.brain.act.ItemConsumer;
import dev.luizloyola.autarkia.core.brain.act.Mover;
import dev.luizloyola.autarkia.core.brain.sense.Percepts;
import dev.luizloyola.autarkia.core.brain.task.Task;
import dev.luizloyola.autarkia.core.brain.task.TaskExecutor;
import dev.luizloyola.autarkia.mod.entity.Person;

/**
 * Per-{@link Person} brain host: mounts the core decision machinery on the entity and gives it a
 * {@link BrainContext} to think through — actuators ({@link PersonMover} legs,
 * {@link PersonItemConsumer} mouth) and percepts ({@link PersonPercepts}). Today that machinery is
 * one {@link TaskExecutor}; the arbiter arrives next ladder step. Only the mounting bracket —
 * everything hosted is pure core, assembled once, the adapters being stateless views.
 *
 * <p>It only ever <em>reads</em> the body, through {@link Percepts}, and never owns body state: the
 * entity owns and ticks its own metabolism ({@link Person#needs()}), so a paused or throttled brain
 * still starves. Anything of the body persists on the entity; the brain's working state (the
 * running task tree) is transient like the Navigator's — a reload just re-decides.
 */
public final class BrainDriver {
    private final TaskExecutor executor = new TaskExecutor();
    /**
     * The one context every task tick receives: the actuator bundle (grows one port per new
     * actuator facade) plus the percept views. Both sides are the only Minecraft boundary the
     * core machinery ever touches.
     */
    private final BrainContext context;

    public BrainDriver(Person person) {
        Mover mover = new PersonMover(person);
        ItemConsumer consumer = new PersonItemConsumer(person);
        Percepts percepts = new PersonPercepts(person);
        ActuatorAccess actuators = new ActuatorAccess() {
            @Override
            public Mover mover() {
                return mover;
            }

            @Override
            public ItemConsumer consumer() {
                return consumer;
            }
        };
        this.context = new BrainContext() {
            @Override
            public ActuatorAccess actuators() {
                return actuators;
            }

            @Override
            public Percepts percepts() {
                return percepts;
            }
        };
    }

    /**
     * One brain tick, from {@link Person#serverAiStep()}. Until the arbiter lands, the only way a
     * task starts is {@link #run}.
     */
    public void tick() {
        this.executor.tick(this.context);
    }

    /** Hands the executor a task (primitive or compound) to run as the root, cancelling any
     *  current one first — the debug commands' entry point today, the arbiter's tomorrow. */
    public void run(Task task) {
        this.executor.run(task, this.context);
    }

    /** Cancels the running task (releasing its actuators); safe to call when idle. */
    public void cancel() {
        this.executor.cancel(this.context);
    }

    public boolean isBusy() {
        return this.executor.isBusy();
    }

    /** The executor's one-line status, for the debug commands. */
    public String describe() {
        return this.executor.describe();
    }
}
