package dev.luizloyola.autarkia.mod.brain;

import dev.luizloyola.autarkia.core.brain.act.ActuatorAccess;
import dev.luizloyola.autarkia.core.brain.task.PrimitiveTask;
import dev.luizloyola.autarkia.core.brain.task.TaskExecutor;
import dev.luizloyola.autarkia.mod.entity.Person;

/**
 * Per-{@link Person} brain host: mounts the core decision machinery on the entity and gives it
 * actuators to act through. Today that machinery is one {@link TaskExecutor}; percept assembly and
 * the arbiter arrive in later ladder steps. Only the mounting bracket and the Minecraft boundary —
 * everything hosted is pure core.
 *
 * <p>It only ever <em>reads</em> the body and never owns body state: the entity owns and ticks its
 * own metabolism ({@link Person#needs()}), so a paused or throttled brain still starves. Anything
 * of the body persists on the entity; the brain's working state (the running task) is transient
 * like the Navigator's — a reload just re-decides.
 */
public final class BrainDriver {
    private final TaskExecutor executor = new TaskExecutor();
    private final PersonMover mover;
    /** The actuator bundle handed to every task tick; grows one port per new actuator facade. */
    private final ActuatorAccess actuators;

    public BrainDriver(Person person) {
        this.mover = new PersonMover(person);
        this.actuators = () -> this.mover;
    }

    /**
     * One brain tick, from {@link Person#serverAiStep()}. Until the arbiter lands, the only way a
     * task starts is {@link #run}.
     */
    public void tick() {
        this.executor.tick(this.actuators);
    }

    /** Hands the executor a task to run, cancelling any current one first — the debug command's
     *  entry point today, the arbiter's tomorrow. */
    public void run(PrimitiveTask task) {
        this.executor.run(task, this.actuators);
    }

    /** Cancels the running task (releasing its actuators); safe to call when idle. */
    public void cancel() {
        this.executor.cancel(this.actuators);
    }

    public boolean isBusy() {
        return this.executor.isBusy();
    }

    /** The executor's one-line status, for the debug commands. */
    public String describe() {
        return this.executor.describe();
    }
}
