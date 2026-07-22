package dev.luizloyola.autarkia.mod.brain;

import dev.luizloyola.autarkia.core.brain.act.MoveState;
import dev.luizloyola.autarkia.core.brain.act.Mover;
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
     * Begin navigating to the cell at {@code (x, y, z)}, replacing any move in progress. Routed
     * through {@link Person#navigateTo(Vec3)} rather than {@code navigator().pathTo} because
     * navigateTo also cancels the debug jump-sprinter, which contends for the forward input.
     */
    @Override
    public void moveTo(int x, int y, int z) {
        this.person.navigateTo(Vec3.atBottomCenterOf(new BlockPos(x, y, z)));
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
