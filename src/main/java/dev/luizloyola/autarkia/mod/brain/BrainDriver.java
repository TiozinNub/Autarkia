package dev.luizloyola.autarkia.mod.brain;

import dev.luizloyola.autarkia.core.brain.Arbiter;
import dev.luizloyola.autarkia.core.brain.BrainContext;
import dev.luizloyola.autarkia.core.brain.board.PersonalBoard;
import dev.luizloyola.autarkia.core.brain.board.WorkItem;
import dev.luizloyola.autarkia.core.brain.board.WorkSource;
import dev.luizloyola.autarkia.core.brain.act.ActuatorAccess;
import dev.luizloyola.autarkia.core.brain.act.BlockBreaker;
import dev.luizloyola.autarkia.core.brain.act.BlockPlacer;
import dev.luizloyola.autarkia.core.brain.act.ItemConsumer;
import dev.luizloyola.autarkia.core.brain.act.Mover;
import dev.luizloyola.autarkia.core.brain.act.Scaffolder;
import dev.luizloyola.autarkia.core.brain.board.PersonClaims;
import dev.luizloyola.autarkia.core.brain.instinct.DescendInstinct;
import dev.luizloyola.autarkia.core.brain.instinct.EatInstinct;
import dev.luizloyola.autarkia.core.brain.instinct.FleeInstinct;
import dev.luizloyola.autarkia.core.brain.instinct.WanderInstinct;
import dev.luizloyola.autarkia.core.brain.knowledge.PersonKnowledge;
import dev.luizloyola.autarkia.core.brain.sense.Percepts;
import dev.luizloyola.autarkia.core.brain.task.Task;
import dev.luizloyola.autarkia.core.inv.ItemSpec;
import dev.luizloyola.autarkia.core.log.Category;
import dev.luizloyola.autarkia.core.log.PersonJournal;
import dev.luizloyola.autarkia.mod.entity.Person;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;

/**
 * Per-{@link Person} brain host: mounts the pure core decision machinery on the entity and hands it
 * a {@link BrainContext} — actuators to act with ({@link PersonMover} legs,
 * {@link PersonItemConsumer} mouth) and {@link PersonPercepts} to sense with. Arbiter-first: an
 * {@link Arbiter} of instincts decides on its own rather than waiting on debug commands. A mounting
 * bracket only, assembled once — the adapters are stateless views over the entity.
 *
 * <p>It only ever <em>reads</em> the body (through {@link Percepts}) and never owns body state: the
 * entity owns and ticks its own metabolism ({@link Person#needs()}), the way vanilla's
 * {@code FoodData} belongs to the player and not to any AI, so a paused brain still starves. That
 * decides what lives where — body state persists on the entity; the brain's working state (arbiter
 * + running task tree) is transient like the Navigator's, and a reload just re-decides.
 */
public final class BrainDriver {
    private final Person person;
    private final Arbiter arbiter;
    /**
     * The one context every task/instinct tick receives: actuators, percepts and the debug
     * journal — the only Minecraft boundary the core machinery ever touches.
     */
    private final BrainContext context;

    /**
     * The autonomy switch. ON (the default — every Person spawns autonomous) means the arbiter
     * decides what to do, tick after tick: the mod's whole point. OFF is the dev override: a
     * manual command has taken the wheel, and only its task runs until autonomy is re-enabled.
     */
    private boolean auto = true;

    /**
     * This person's knowledge view, resolved lazily on first use and cached — the journal's
     * pattern, for the same reason: the {@code PersonId} and the running server are absent at
     * construction but guaranteed by the time any task asks (identity resolves at the top of
     * {@code Person.tick()}, before the brain runs).
     */
    private PersonKnowledge knowledge;

    /**
     * This person's view of the server-shared work-site claims — resolved lazily exactly like
     * {@link #knowledge}, and for the same reason (the {@code PersonId} arrives after
     * construction).
     */
    private PersonClaims claims;

    /**
     * This person's personal board (layer 3's degenerate v1): the hardcoded keep-16-logs
     * stock rule every fresh spawn wants — the placeholder demand generator, retired when the
     * first real project (the axe) posts its own bill of materials. Transient: items
     * regenerate from the inventory predicate.
     */
    private final PersonalBoard board;

    /** The placeholder stock rule: keep this many logs, at this standing priority. */
    private static final int STOCK_LOGS = 16;
    private static final double STOCK_PRIORITY = 0.35;

    public BrainDriver(Person person) {
        this.person = person;
        // Offset the board cadence by entity id so a settlement doesn't re-plan in lockstep.
        this.board = new PersonalBoard(ItemSpec.LOGS, STOCK_LOGS, STOCK_PRIORITY, person.getId());
        Mover mover = new PersonMover(person);
        ItemConsumer consumer = new PersonItemConsumer(person);
        BlockPlacer placer = new PersonBlockPlacer(person);
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

            @Override
            public BlockBreaker breaker() {
                // Owned and ticked by the body (crack/drops/exhaustion advance with the
                // entity); the driver only lends it out as a port.
                return person.blockBreaker();
            }

            @Override
            public BlockPlacer placer() {
                return placer; // one-shot port: stateless view, driver-owned like the consumer
            }

            @Override
            public Scaffolder scaffolder() {
                return person.scaffolder(); // body-owned and body-ticked, like the breaker
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
            public PersonJournal journal() {
                return person.journal(); // the entity owns the one cached view; the body/nav share it
            }

            @Override
            public PersonKnowledge knowledge() {
                return resolveKnowledge();
            }

            @Override
            public PersonClaims claims() {
                return resolveClaims();
            }

            @Override
            public double costTolerance() {
                // Manual driving answers to no pressure: a dev-issued task runs to completion (or
                // failure) on its own terms rather than getting judged against the arbiter's
                // budget while autonomy is off.
                return auto ? arbiter.costTolerance() : Double.POSITIVE_INFINITY;
            }
        };
        // RandomSource isn't a java.util.random.RandomGenerator, so it can't reach the instincts
        // directly; it seeds this one once at construction instead. Both random-driven instincts
        // (Flee's scatter, Wander's roam) draw from it — one stream per brain, never shared.
        Random random = new Random(person.getRandom().nextLong());
        // The board reaches the arbiter through a celebrating wrapper: a completed errand gets a
        // beat in the world (decision: Luiz) before the core board hears of it. sendParticles
        // broadcasts to every tracking client; no custom networking.
        WorkSource celebrating = new WorkSource() {
            @Override
            public Optional<WorkItem> bestAvailable(BrainContext c) {
                return board.bestAvailable(c);
            }

            @Override
            public void claimed(WorkItem item, BrainContext c) {
                board.claimed(item, c);
            }

            @Override
            public void completed(WorkItem item, BrainContext c) {
                celebrate();
                board.completed(item, c);
            }

            @Override
            public void failed(WorkItem item, BrainContext c) {
                board.failed(item, c);
            }
        };
        // Flee is first on purpose: the arbiter breaks pressure ties in list order, so an exact
        // flee/eat tie must resolve to fleeing. Descend sits between the needs and wander: a
        // stranded body gets down before it drifts, but never before it flees or eats urgently.
        this.arbiter = new Arbiter(List.of(
                new FleeInstinct(random), new EatInstinct(), new DescendInstinct(),
                new WanderInstinct(random)),
                celebrating);
    }

    private void celebrate() {
        ServerLevel level = (ServerLevel) person.level();
        level.sendParticles(ParticleTypes.HAPPY_VILLAGER,
                person.getX(), person.getY() + 1.4, person.getZ(), 12, 0.4, 0.5, 0.4, 0.0);
        level.playSound(null, person.blockPosition(), SoundEvents.VILLAGER_YES,
                SoundSource.NEUTRAL, 1.0F, 1.0F);
    }

    /**
     * One brain tick, from {@link Person#serverAiStep()}. Autonomous: the arbiter decides (and
     * drives its own executor). Manual: only the executor advances — the arbiter stays dormant so
     * a dev-issued task isn't second-guessed mid-flight.
     */
    public void tick() {
        // The board thinks on its own slow cadence regardless of who is driving — posting and
        // withdrawing are demand bookkeeping, not action; only the arbiter CLAIMS.
        this.board.tick(this.context);
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
        // BRAIN log: the dev override is worth a line — it explains a gap where the arbiter went quiet.
        this.person.journal().record(Category.BRAIN, "auto", auto ? "on" : "off");
    }

    /** The person's knowledge store, resolved once and cached — see {@link #knowledge}. */
    private PersonKnowledge resolveKnowledge() {
        if (this.knowledge == null) {
            ServerLevel level = (ServerLevel) this.person.level();
            this.knowledge = Knowledges.of(level.getServer()).forPerson(this.person.getPersonId());
        }
        return this.knowledge;
    }

    /** The person's claims view, resolved once and cached — see {@link #claims}. */
    private PersonClaims resolveClaims() {
        if (this.claims == null) {
            ServerLevel level = (ServerLevel) this.person.level();
            this.claims = Claims.of(level.getServer()).forPerson(this.person.getPersonId());
        }
        return this.claims;
    }

    /** The personal board's status line — see {@link PersonalBoard#describe}. */
    public String describeBoard() {
        return this.board.describe(this.context);
    }

    /** The brain's one-line status, for the debug commands: which side is driving, then its report. */
    public String describe() {
        return this.auto
                ? "auto | " + this.arbiter.describe()
                : "manual | " + this.arbiter.executor().describe();
    }
}
