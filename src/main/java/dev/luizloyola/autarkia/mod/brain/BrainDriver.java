package dev.luizloyola.autarkia.mod.brain;

import dev.luizloyola.autarkia.core.brain.Arbiter;
import dev.luizloyola.autarkia.core.brain.BrainContext;
import dev.luizloyola.autarkia.core.brain.act.ActuatorAccess;
import dev.luizloyola.autarkia.core.brain.act.ItemConsumer;
import dev.luizloyola.autarkia.core.brain.act.Mover;
import dev.luizloyola.autarkia.core.brain.instinct.EatInstinct;
import dev.luizloyola.autarkia.core.brain.instinct.WanderInstinct;
import dev.luizloyola.autarkia.core.brain.sense.Percepts;
import dev.luizloyola.autarkia.core.brain.task.Task;
import dev.luizloyola.autarkia.mod.entity.Person;
import java.util.List;
import java.util.Random;

/**
 * Per-{@link Person} brain host: mounts the core decision machinery on the entity and gives it a
 * {@link BrainContext} to think through — actuators ({@link PersonMover} legs,
 * {@link PersonItemConsumer} mouth) and percepts ({@link PersonPercepts}). Arbiter-first since
 * ladder step 4: it hosts an {@link Arbiter} (Eat + Wander today) rather than a bare
 * {@link dev.luizloyola.autarkia.core.brain.task.TaskExecutor}. Only the mounting bracket —
 * everything hosted is pure core, assembled once, the adapters being stateless views.
 *
 * <p>It only ever <em>reads</em> the body, through {@link Percepts}, and never owns body state: the
 * entity owns and ticks its own metabolism ({@link Person#needs()}), so a paused or throttled brain
 * still starves. Anything of the body persists on the entity; the brain's working state (arbiter +
 * running task tree) is transient like the Navigator's — a reload just re-decides.
 */
public final class BrainDriver {
    private final Arbiter arbiter;
    /**
     * The one context every task/instinct tick receives: the actuator bundle (grows one port per
     * new actuator facade) plus the percept views. Both sides are the only Minecraft boundary the
     * core machinery ever touches.
     */
    private final BrainContext context;

    /**
     * The autonomy switch. ON (the default — every Person spawns autonomous) means the arbiter
     * decides what to do, tick after tick: the mod's whole point. OFF is the dev override: a
     * manual command has taken the wheel, and only its task runs until autonomy is re-enabled.
     */
    private boolean auto = true;

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

            @Override
            public double costTolerance() {
                // Manual driving answers to no pressure: a dev-issued task runs to completion (or
                // failure) on its own terms rather than getting judged against the arbiter's
                // budget while autonomy is off.
                return auto ? arbiter.costTolerance() : Double.POSITIVE_INFINITY;
            }
        };
        // RandomSource is not a java.util.random.RandomGenerator, so it seeds a fresh
        // java.util.Random once at construction — per-entity, not shared, not reproducible.
        Random random = new Random(person.getRandom().nextLong());
        this.arbiter = new Arbiter(List.of(new EatInstinct(), new WanderInstinct(random)));
    }

    /**
     * One brain tick, from {@link Person#serverAiStep()}. Autonomous: the arbiter decides (and
     * drives its own executor). Manual: only the executor advances — the arbiter stays dormant so
     * a dev-issued task isn't second-guessed mid-flight.
     */
    public void tick() {
        if (this.auto) {
            this.arbiter.tick(this.context);
        } else {
            this.arbiter.executor().tick(this.context);
        }
    }

    /**
     * Hands the executor a root task, cancelling any current one — the debug commands' entry
     * point. Always takes the wheel: autonomy goes off, and {@code true} comes back exactly when
     * this call is what disabled it.
     */
    public boolean run(Task task) {
        boolean wasAuto = this.auto;
        if (this.auto) {
            this.auto = false;
        }
        this.arbiter.executor().run(task, this.context);
        return wasAuto;
    }

    /** Cancels the running task (releasing its actuators); safe to call when idle. */
    public void cancel() {
        this.arbiter.executor().cancel(this.context);
    }

    public boolean isBusy() {
        return this.arbiter.executor().isBusy();
    }

    /** Whether the arbiter is currently deciding (ON) or a manual task has the wheel (OFF). */
    public boolean isAuto() {
        return this.auto;
    }

    /** Flips the autonomy switch — see {@link #auto}. */
    public void setAuto(boolean auto) {
        this.auto = auto;
    }

    /** The brain's one-line status, for the debug commands: which side is driving, then its report. */
    public String describe() {
        return this.auto
                ? "auto | " + this.arbiter.describe()
                : "manual | " + this.arbiter.executor().describe();
    }
}
