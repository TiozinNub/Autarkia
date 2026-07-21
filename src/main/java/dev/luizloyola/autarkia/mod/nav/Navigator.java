package dev.luizloyola.autarkia.mod.nav;

import dev.luizloyola.autarkia.mod.entity.Person;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * Steers a single {@link Person} toward a target position, once per server tick, by feeding its
 * movement controls ({@link Person#driveForward}/{@link Person#stopMoving}) — the seam between
 * (future) pathfinding and locomotion.
 *
 * <p>It walks a straight line; obstacle avoidance is out of scope (v1 assumes flat terrain). The
 * public surface ({@link #moveTo}, {@link #stop}, {@link #state()}) is what a computed A* path will
 * drive too: the target becomes a path's final waypoint and {@link #tick()} advances through the
 * intermediate ones.
 */
public final class Navigator {
    public enum State { IDLE, MOVING, ARRIVED }

    /**
     * Horizontal distance (blocks) at which the target counts as reached. Under one block, so a
     * Person settles on the target cell rather than overshooting into the next; well over one
     * tick's ~0.22-block walk step, so it never jitters.
     */
    private static final double ARRIVAL_RADIUS = 0.6;

    private final Person person;
    private @Nullable Vec3 target;
    private State state = State.IDLE;

    public Navigator(Person person) {
        this.person = person;
    }

    /** Begin walking toward {@code target}, replacing any target already in progress. */
    public void moveTo(Vec3 target) {
        this.target = target;
        this.state = State.MOVING;
    }

    /** Abandon the current target and hold position. */
    public void stop() {
        this.target = null;
        this.state = State.IDLE;
        this.person.stopMoving();
    }

    public State state() {
        return this.state;
    }

    /** The current target, or {@code null} when not moving. */
    public @Nullable Vec3 target() {
        return this.target;
    }

    /**
     * One tick of steering, driven from {@link Person#serverAiStep()}. On arrival it stops and
     * flips to {@link State#ARRIVED}; otherwise it holds the input at rest, so a stopped Person
     * does not coast on stale input.
     */
    public void tick() {
        if (this.state != State.MOVING || this.target == null) {
            this.person.stopMoving();
            return;
        }
        Vec3 pos = this.person.position();
        double dx = this.target.x - pos.x;
        double dz = this.target.z - pos.z;
        // Horizontal only: v1 is flat, so target y equals the Person's y and vertical distance is
        // noise. (Elevation arrival is the pathfinder's problem, once it can climb.)
        if (dx * dx + dz * dz <= ARRIVAL_RADIUS * ARRIVAL_RADIUS) {
            this.target = null;
            this.state = State.ARRIVED;
            this.person.stopMoving();
            return;
        }
        // Minecraft yaw: 0 faces +Z (south) and increases clockwise, so forward = (-sin y, cos y).
        // Facing a point is therefore atan2(dz, dx) in degrees, offset by -90.
        float heading = (float) (Mth.atan2(dz, dx) * Mth.RAD_TO_DEG) - 90.0F;
        this.person.driveForward(heading);
    }
}
