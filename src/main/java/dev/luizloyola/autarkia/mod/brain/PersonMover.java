package dev.luizloyola.autarkia.mod.brain;

import dev.luizloyola.autarkia.core.brain.act.MoveState;
import dev.luizloyola.autarkia.core.brain.act.Mover;
import dev.luizloyola.autarkia.core.nav.Gait;
import dev.luizloyola.autarkia.mod.entity.Person;
import dev.luizloyola.autarkia.mod.nav.Navigator;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

/**
 * The {@link Mover} actuator <em>adapter</em>: core tasks see a version-neutral movement port,
 * mapped here onto the {@link Navigator}, which stays the single owner of locomotion — pathing,
 * following, re-pathing, every per-tick steering decision. When a task wants something the port
 * cannot say, the port grows; the adapter never leaks Navigator internals upward.
 */
public final class PersonMover implements Mover {
    private final Person person;

    public PersonMover(Person person) {
        this.person = person;
    }

    /**
     * Begin navigating to the cell at {@code (x, y, z)}, replacing any move in progress. The two
     * gait branches route DIFFERENTLY on purpose:
     *
     * <ul>
     *   <li><b>{@link Gait#WALK}</b> (the {@link Mover#moveTo(int, int, int)} default) goes through
     *       {@link Person#navigateTo(Vec3)}, which cancels the debug jump-sprinter — it and the
     *       navigator both own the forward input.
     *   <li><b>{@link Gait#SPRINT} / {@link Gait#STROLL}</b> go straight to
     *       {@link Navigator#pathTo(BlockPos, Gait)} and so do <em>not</em> cancel it: accepted
     *       asymmetry, since a paced move issued while that toy holds the legs waits for it
     *       to be switched off.
     * </ul>
     */
    @Override
    public void moveTo(int x, int y, int z, Gait gait) {
        BlockPos cell = new BlockPos(x, y, z);
        if (gait == Gait.WALK) {
            this.person.navigateTo(Vec3.atBottomCenterOf(cell));
        } else {
            this.person.navigator().pathTo(cell, gait);
        }
    }

    /**
     * The Navigator's lifecycle folded onto the port's four states. PATHING reports as MOVING — a
     * move is committed the moment it is requested; the wait on the off-thread route is the
     * Navigator's business, not a task's.
     */
    @Override
    public MoveState state() {
        return switch (this.person.navigator().state()) {
            case IDLE -> MoveState.IDLE;
            case PATHING, FOLLOWING -> MoveState.MOVING;
            case ARRIVED -> MoveState.ARRIVED;
            case FAILED -> MoveState.FAILED;
        };
    }

    @Override
    public void stop() {
        this.person.navigator().stop();
    }
}
