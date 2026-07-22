package dev.luizloyola.autarkia.core.brain.task;

import dev.luizloyola.autarkia.core.brain.act.ActuatorAccess;

/**
 * Runs one {@link PrimitiveTask} at a time — the execution slot of the task layer. The arbiter
 * will feed it once it exists; today the debug command does.
 *
 * <p>Ticked by the mod {@code BrainDriver} from {@code serverAiStep} before the Navigator ticks,
 * so actuator orders a task issues in {@link #tick} are acted on that same tick.
 *
 * <p>Lifecycle, all in service of "the body has one set of legs":
 * <ul>
 *   <li>{@link #run} while busy cancels the incumbent first — its actuators must be released
 *       before the newcomer touches them — then installs the new task without ticking it;</li>
 *   <li>a terminal status clears the slot and is remembered (description + status) for the debug
 *       readout;</li>
 *   <li>{@link #cancel} clears without recording, so a cancelled task cannot overwrite the last
 *       real outcome.</li>
 * </ul>
 */
public final class TaskExecutor {
    private PrimitiveTask current;
    private String lastDescription;
    private TaskStatus lastStatus;

    /**
     * Install a task as the one being executed, preempting (cancelling) any incumbent first.
     * Does not tick the newcomer — the next {@link #tick} does, so a task's first decision
     * always happens at the normal point in the tick order.
     */
    public void run(PrimitiveTask task, ActuatorAccess actuators) {
        if (current != null) {
            current.cancel(actuators);
        }
        current = task;
    }

    /**
     * Cancel and clear the current task, if any. Records nothing: {@link #describe} goes back to
     * an idle reading with the previous terminal outcome (if any) intact.
     */
    public void cancel(ActuatorAccess actuators) {
        if (current != null) {
            current.cancel(actuators);
            current = null;
        }
    }

    /**
     * One executor tick: a no-op when idle, otherwise the current task's {@link
     * PrimitiveTask#tick}. On a terminal status the slot is cleared and the outcome remembered,
     * so a finished task is never ticked again (the {@link PrimitiveTask} contract).
     */
    public void tick(ActuatorAccess actuators) {
        if (current == null) {
            return;
        }
        TaskStatus status = current.tick(actuators);
        if (status != TaskStatus.RUNNING) {
            lastDescription = current.describe();
            lastStatus = status;
            current = null;
        }
    }

    public boolean isBusy() {
        return current != null;
    }

    /**
     * The debug readout: {@code "running: <task>"} while busy, {@code "idle (last: <task> ->
     * <status>)"} after a terminal outcome, plain {@code "idle"} when nothing ever finished.
     */
    public String describe() {
        if (current != null) {
            return "running: " + current.describe();
        }
        if (lastStatus == null) {
            return "idle";
        }
        return "idle (last: " + lastDescription + " -> " + lastStatus + ")";
    }
}
