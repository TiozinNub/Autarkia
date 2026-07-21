package dev.luizloyola.autarkia.mod.nav;

import dev.luizloyola.autarkia.core.nav.AgentProfile;
import dev.luizloyola.autarkia.core.nav.MoveType;
import dev.luizloyola.autarkia.core.nav.NavGrid;
import dev.luizloyola.autarkia.core.nav.NavGrids;
import dev.luizloyola.autarkia.core.nav.Path;
import dev.luizloyola.autarkia.core.nav.Waypoint;
import dev.luizloyola.autarkia.mod.entity.Person;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * Per-{@link Person} navigation state machine: asks the {@link PathfinderService} for a
 * {@link Path} and walks it waypoint by waypoint, one steering decision per server tick, through
 * {@link Person#driveForward}/{@link Person#driveJump}/{@link Person#stopMoving}.
 *
 * <p>Lifecycle: {@code IDLE → PATHING → FOLLOWING → ARRIVED}, plus {@code FAILED}. It re-paths when
 * progress stalls or a partial path runs out short of the goal, each retry spending one of a small
 * budget so an unreachable goal fails instead of thrashing.
 *
 * <p>{@code PATHING} means an off-thread request is in flight; its result is only ever read from
 * {@link #tick()}, inside {@code serverAiStep}, so paths are applied on the main thread.
 */
public final class Navigator {
    public enum State { IDLE, PATHING, FOLLOWING, ARRIVED, FAILED }

    /**
     * Horizontal arrival radius for the final waypoint: under a block, so the body settles on the
     * goal cell rather than the next, and over one tick's ~0.22-block step so it never orbits.
     */
    private static final double FINAL_RADIUS = 0.55;
    /** Intermediate waypoints are corners, not destinations — cut them tighter so turns hug the path. */
    private static final double WAYPOINT_RADIUS = 0.4;
    /**
     * Careful mode, active while the ground being crossed borders a deep drop / lava / water
     * ({@link NavGrids#isNearDeepDrop}): forward input throttled to about half walk speed and
     * waypoints hit tighter. JUMP and LEAP are exempt — a leap's takeoff is edge-adjacent by
     * definition and needs full speed.
     */
    private static final float CAREFUL_THROTTLE = 0.45F;
    private static final double CAREFUL_RADIUS = 0.25;
    /** Final-waypoint radius when the goal cell borders a drop — see the radius selection. */
    private static final double CAREFUL_FINAL_RADIUS = 0.35;
    /**
     * Residual slide (blocks/tick, squared) below which a landing counts as settled: 0.02/tick.
     * Friction leaves ~1.2× the current speed as drift, bounding post-arrival creep to ~0.025
     * blocks; at 0.1/tick the creep measured ~0.13 and the Person glided onto the block lip.
     */
    private static final double SETTLED_SPEED_SQ = 0.0004;
    /**
     * How close (horizontally) a JUMP waypoint must be before the jump is pressed. It becomes
     * current up to ~1.4 away; 1.0 waits until we are inside the takeoff cell (adjacent centres are
     * 1.0 apart), so the body doesn't leap early and land short.
     */
    private static final double JUMP_RANGE = 1.0;
    /**
     * How many waypoints ahead the passed-node check may claim. Overshoots (a jump carries
     * ~1.5 blocks, knockback a couple more) land at most a few cells down the path, so a short
     * window catches every real case in O(1) instead of scanning the whole path each tick.
     */
    private static final int SKIP_LOOKAHEAD = 8;
    /**
     * Grounded distance from the current waypoint beyond which we are off the path and re-path from
     * where we stand. Must stay ABOVE the engine's longest stride — a (3,3) step puts the next
     * waypoint 3√2 ≈ 4.25 away, plus the 0.4 advance radius.
     */
    private static final double STRAY_HORIZONTAL = 5.0;
    private static final double STRAY_VERTICAL = 1.5;
    /** Ticks without reaching the next waypoint (one cell away!) before declaring ourselves stuck. */
    private static final int STUCK_LIMIT = 60;
    /**
     * The fast stuck path: consecutive grounded ticks driven forward but barely moving
     * (&lt; {@link #NO_MOVE_EPSILON} vs ~0.22 expected at walk) — wedged on something the snapshot
     * didn't know. Re-paths in ~0.75s instead of the 60-tick timer, yet outlasts the 1–2 ticks
     * pressed against a ledge before a jump.
     */
    private static final int NO_MOVE_LIMIT = 15;
    private static final double NO_MOVE_EPSILON = 0.01;
    /** Re-path budget per {@link #pathTo} request: stuck or short-of-goal retries, then FAILED. */
    private static final int MAX_REPATHS = 3;

    /** Dev-phase visuals: END_ROD breadcrumbs on the remaining path, FLAME on the goal. */
    public static boolean debugParticles = true;
    /**
     * Debug escape hatch: {@code false} runs the search synchronously on the server thread
     * (identical pipeline, no executor), taking threading out of the picture when chasing a
     * pathfinding bug.
     */
    private static final boolean OFF_THREAD = true;

    private final Person person;
    private State state = State.IDLE;
    private @Nullable BlockPos goal;
    private @Nullable Path path;
    /** The snapshot the current path was planned over; the follower reads it for edge awareness. */
    private @Nullable NavGrid grid;
    private int index;
    private int stuckTicks;
    private int noMoveTicks;
    private double lastTickX;
    private double lastTickZ;
    private int repathsLeft;
    private @Nullable CompletableFuture<Path> pending;

    public Navigator(Person person) {
        this.person = person;
    }

    /** Begin navigating toward {@code goal}, replacing any navigation already in progress. */
    public void pathTo(BlockPos goal) {
        stop();
        this.goal = goal;
        this.repathsLeft = MAX_REPATHS;
        requestPath();
    }

    /** Abandon the current goal and hold position. */
    public void stop() {
        if (this.pending != null) {
            this.pending.cancel(false);
            this.pending = null;
        }
        this.goal = null;
        this.path = null;
        this.grid = null;
        this.index = 0;
        this.state = State.IDLE;
        this.person.stopMoving();
    }

    public State state() {
        return this.state;
    }

    /** The current goal cell, or {@code null} when idle. Survives ARRIVED/FAILED for inspection. */
    public @Nullable BlockPos goal() {
        return this.goal;
    }

    /** One-line progress summary for the debug command. */
    public String describe() {
        StringBuilder text = new StringBuilder(this.state.toString());
        if (this.goal != null) {
            text.append(" -> ").append(this.goal.toShortString());
        }
        if (this.state == State.FOLLOWING && this.path != null) {
            text.append(" (waypoint ").append(this.index + 1).append('/')
                    .append(this.path.waypoints().size())
                    .append(this.path.reachedGoal() ? ")" : ", partial)");
        }
        return text.toString();
    }

    /**
     * One tick of navigation, driven from {@link Person#serverAiStep()}. Exactly one movement
     * decision leaves here per tick; in every state but FOLLOWING that decision is "stand still",
     * so a stopped Person never coasts on stale input.
     */
    public void tick() {
        switch (this.state) {
            case PATHING -> tickPathing();
            case FOLLOWING -> tickFollowing();
            default -> this.person.stopMoving();
        }
    }

    /** Fires the (off-thread) path computation for the current goal. */
    private void requestPath() {
        this.path = null;
        this.index = 0;
        this.stuckTicks = 0;
        this.noMoveTicks = 0;
        this.state = State.PATHING;
        PathfinderService.Dispatched dispatched = OFF_THREAD
                ? PathfinderService.request(level(), this.person.blockPosition(), this.goal)
                : PathfinderService.computeNow(level(), this.person.blockPosition(), this.goal);
        this.grid = dispatched.snapshot();
        this.pending = dispatched.result();
    }

    /** Polls the in-flight request; the future completes on a worker, so only ever read it here. */
    private void tickPathing() {
        this.person.stopMoving();
        if (this.pending == null || !this.pending.isDone()) {
            return;
        }
        CompletableFuture<Path> done = this.pending;
        this.pending = null;
        try {
            acceptPath(done.join());
        } catch (CompletionException | CancellationException e) {
            // Worker died or the server is stopping mid-request; either way there is no path.
            this.state = State.FAILED;
        }
    }

    private void acceptPath(Path result) {
        if (result.reachedGoal() && result.isEmpty()) {
            this.state = State.ARRIVED; // already standing on the goal
            return;
        }
        // A partial path that ends about where we stand is the search saying "no way through":
        // retrying from the same spot would just repeat it, so fail rather than loop.
        if (result.isEmpty() || (!result.reachedGoal() && endsWhereWeStand(result))) {
            this.state = State.FAILED;
            return;
        }
        this.path = result;
        this.index = 0;
        this.stuckTicks = 0;
        this.state = State.FOLLOWING;
    }

    private boolean endsWhereWeStand(Path result) {
        Waypoint last = result.last();
        return this.person.blockPosition().distSqr(new BlockPos(last.x(), last.y(), last.z())) <= 2.0;
    }

    private void tickFollowing() {
        skipPassedWaypoints();
        advancePassedPlanes(this.person.position());
        Waypoint waypoint = this.path.waypoints().get(this.index);
        boolean isLast = this.index == this.path.waypoints().size() - 1;
        Vec3 pos = this.person.position();
        double dx = waypoint.x() + 0.5 - pos.x;
        double dz = waypoint.z() + 0.5 - pos.z;
        double horizontalSq = dx * dx + dz * dz;
        double dy = pos.y - waypoint.y();

        // Standing far from the current waypoint means we've left the path (see STRAY_HORIZONTAL),
        // and steering back would be walking blind, so plan fresh from here. Grounded-only —
        // airtime is never a stray. The above-tolerance is asymmetric for DROP waypoints: at the
        // brink the body is legitimately up to maxDrop above its landing, and without that any
        // 2–3-block drop burned every retry.
        double aboveTolerance = waypoint.move() == MoveType.DROP
                ? AgentProfile.PERSON.maxDrop() + 0.5
                : STRAY_VERTICAL;
        if (this.person.onGround()
                && (horizontalSq > STRAY_HORIZONTAL * STRAY_HORIZONTAL
                        || dy > aboveTolerance || dy < -STRAY_VERTICAL)) {
            retryOrFail();
            return;
        }

        // Leap phases: full speed through approach and flight; grounded near the landing ("landing
        // phase") the sprint cuts, and if that cell borders another drop careful mode takes the
        // settling ticks so the landing momentum can't carry us over the far edge.
        double leapSpan = waypoint.move() == MoveType.LEAP ? leapSpan(waypoint) : 0.0;
        boolean leapLanding = waypoint.move() == MoveType.LEAP && this.person.onGround()
                && horizontalSq <= 1.44;
        boolean careful = isCareful(waypoint, leapLanding);
        // A careful FINAL waypoint also shrinks the arrival radius: 0.55 from the center of a
        // 1-wide block is its lip — "arrived" next to a drop must mean standing well inside.
        double radius = isLast ? (careful ? CAREFUL_FINAL_RADIUS : FINAL_RADIUS)
                : careful ? CAREFUL_RADIUS : WAYPOINT_RADIUS;
        // Arrival is one 3-D distance, horizontal offset and vertical gap against the radius, so
        // "close enough" can't be a block above or below the waypoint. The final waypoint also
        // needs ground under the feet — a leap can sail over the goal, and cutting input airborne
        // lets the landing skid pick the endpoint.
        double vertical = verticalGap(dy);
        if (horizontalSq + vertical * vertical <= radius * radius
                && (!isLast || this.person.onGround())) {
            if (isLast && !isSettled()) {
                // Inside the radius but still sliding: cut input and let friction bleed it off, or
                // the machine freezes while momentum picks the real endpoint. If the slide leaves
                // the radius, careful steering walks us back.
                this.person.stopMoving();
                return;
            }
            advance(isLast);
            return;
        }

        if (++this.stuckTicks > STUCK_LIMIT) {
            // One cell should never take this long: something the snapshot didn't know is in the
            // way (or we fell somewhere unplanned). Re-path from wherever we actually are.
            retryOrFail();
            return;
        }

        // The fast stuck path (see NO_MOVE_LIMIT): input has been driving but the feet aren't
        // going anywhere. Positions are tick-start values, so the delta measures what the last
        // tick's input actually achieved.
        double movedSq = (pos.x - this.lastTickX) * (pos.x - this.lastTickX)
                + (pos.z - this.lastTickZ) * (pos.z - this.lastTickZ);
        this.lastTickX = pos.x;
        this.lastTickZ = pos.z;
        if (this.person.onGround() && movedSq < NO_MOVE_EPSILON * NO_MOVE_EPSILON) {
            if (++this.noMoveTicks > NO_MOVE_LIMIT) {
                retryOrFail();
                return;
            }
        } else {
            this.noMoveTicks = 0;
        }

        // Steering aim: normally the current waypoint — but airborne with it under or BEHIND our
        // motion, aiming at it would flip the heading 180° and land us back-to-front. The proximity
        // term catches "directly above it, falling straight"; the dot-product catches "already past
        // it and moving on". Aim at the next waypoint instead; over the final waypoint, hold the
        // heading we have.
        double aimX = dx;
        double aimZ = dz;
        Vec3 velocity = this.person.getDeltaMovement();
        boolean overTarget = !this.person.onGround()
                && (horizontalSq < 0.25 || dx * velocity.x + dz * velocity.z < 0.0);
        boolean hasNext = this.index + 1 < this.path.waypoints().size();
        if (overTarget && hasNext) {
            Waypoint next = this.path.waypoints().get(this.index + 1);
            aimX = next.x() + 0.5 - pos.x;
            aimZ = next.z() + 0.5 - pos.z;
        }
        // Airborne over the FINAL waypoint there is nothing to aim past it — hold the facing and
        // COAST. Driving forward air-accelerated us beyond the goal, forcing a landing past it and
        // a walk back.
        boolean coastToLanding = overTarget && !hasNext;
        // Minecraft yaw: 0 faces +Z (south) and increases clockwise, so facing a point is
        // atan2(dz, dx) in degrees, offset by -90.
        float heading = coastToLanding
                ? this.person.getYRot()
                : (float) (Mth.atan2(aimZ, aimX) * Mth.RAD_TO_DEG) - 90.0F;
        // Gait before forward input: driveForward reads the speed attribute, which sprinting
        // modifies. Only a 2+ leap approach/flight sprints (the run-up); everything else walks,
        // slowly near a drop. A DROP into the final waypoint takes the careful speed even on safe
        // ground: the glide through a 3-block fall is ~1.5 blocks at full walk, ~0.6 at 0.45.
        boolean precisionFinal = isLast && waypoint.move() == MoveType.DROP;
        this.person.driveSprint(leapSpan >= 3.0 && !leapLanding);
        this.person.driveForward(heading,
                coastToLanding ? 0.0F : careful || precisionFinal ? CAREFUL_THROTTLE : 1.0F);
        if (waypoint.move() == MoveType.JUMP && dy < -0.5
                && horizontalSq < JUMP_RANGE * JUMP_RANGE && this.person.onGround()) {
            // A full block up needs a real jump (step height covers only 0.6); aiStep's ground check
            // fires it the moment we are grounded and close. The dy guard keeps the press to while
            // we are still BELOW the ledge — a re-press from on top would launch us off the far side.
            this.person.driveJump();
        } else if (waypoint.move() == MoveType.LEAP && this.person.onGround()) {
            // Leap: run (or sprint) at the gap and press jump right at the edge. The takeoff cell's
            // far rim sits at span−0.5 from the landing center, so pressing inside span−0.2 launches
            // within a step of it and the arc clears the gap.
            double press = leapSpan - 0.2;
            if (horizontalSq <= press * press) {
                this.person.driveJump();
            }
        }

        if (debugParticles && this.person.tickCount % 5 == 0) {
            emitPathParticles();
        }
    }

    /**
     * Claims every waypoint we are already past: standing in the cell of a <em>later</em> one
     * (overshot jump, knockback) continues from there. Safe by construction — we only skip to a
     * node we are standing on, so no skipped stretch can hide an obstacle, and A* never revisits a
     * cell, so a match is unambiguous. The final waypoint is made current rather than claimed, so
     * the arrival radius still settles us on the goal.
     */
    private void skipPassedWaypoints() {
        BlockPos feet = this.person.blockPosition();
        double y = this.person.position().y;
        int last = this.path.waypoints().size() - 1;
        int limit = Math.min(this.index + SKIP_LOOKAHEAD, last);
        for (int j = limit; j > this.index; j--) {
            Waypoint w = this.path.waypoints().get(j);
            if (feet.getX() == w.x() && feet.getZ() == w.z() && verticalGap(y - w.y()) < 0.5) {
                this.index = Math.min(j + 1, last);
                this.stuckTicks = 0;
                return;
            }
        }
    }

    /**
     * Plane-crossing advance: a waypoint is passed once we are beyond the perpendicular plane
     * through it along the segment to the next one — at its height and within a 2-block lane. A
     * drop's glide lands between waypoints, and without this the steering aims back at the one
     * behind for a tick. Never touches the final waypoint, and the vertical gate keeps a JUMP
     * unclaimable from below its ledge.
     */
    private void advancePassedPlanes(Vec3 pos) {
        while (this.index < this.path.waypoints().size() - 1) {
            Waypoint current = this.path.waypoints().get(this.index);
            Waypoint next = this.path.waypoints().get(this.index + 1);
            double offX = pos.x - (current.x() + 0.5);
            double offZ = pos.z - (current.z() + 0.5);
            if (verticalGap(pos.y - current.y()) >= 0.5
                    || offX * offX + offZ * offZ > 4.0
                    || offX * (next.x() - current.x()) + offZ * (next.z() - current.z()) <= 0.0) {
                return;
            }
            this.index++;
            this.stuckTicks = 0;
        }
    }

    /** Whether residual horizontal momentum has bled off (see {@link #SETTLED_SPEED_SQ}). */
    private boolean isSettled() {
        Vec3 velocity = this.person.getDeltaMovement();
        return velocity.x * velocity.x + velocity.z * velocity.z < SETTLED_SPEED_SQ;
    }

    /**
     * Vertical distance from a waypoint's <em>standing band</em>: feet legitimately rest anywhere
     * from the cell floor to half a block above it (slabs, stair bottoms), so that range counts
     * as zero and the gap grows outside it. Keeps 3-D arrival checks strict without making every
     * slab cell unreachable.
     */
    private static double verticalGap(double dy) {
        if (dy < 0.0) return -dy;
        return Math.max(0.0, dy - 0.5);
    }

    /**
     * Whether this moment warrants careful mode (see {@link #CAREFUL_THROTTLE}). Walk and drop
     * stretches are careful when the waypoint or our own feet cell borders a deep drop; a LEAP only
     * in its landing phase, and only when the landing cell itself borders another drop. Checked
     * against the grid the path was planned on.
     */
    private boolean isCareful(Waypoint waypoint, boolean leapLanding) {
        if (this.grid == null) {
            return false;
        }
        int maxDrop = AgentProfile.PERSON.maxDrop();
        MoveType move = waypoint.move();
        if (move == MoveType.LEAP) {
            return leapLanding
                    && NavGrids.isNearDeepDrop(this.grid, maxDrop, waypoint.x(), waypoint.y(), waypoint.z());
        }
        if (move != MoveType.WALK && move != MoveType.DROP) {
            return false;
        }
        if (NavGrids.isNearDeepDrop(this.grid, maxDrop, waypoint.x(), waypoint.y(), waypoint.z())) {
            return true;
        }
        BlockPos feet = this.person.blockPosition();
        return NavGrids.isNearDeepDrop(this.grid, maxDrop, feet.getX(), feet.getY(), feet.getZ());
    }

    /**
     * Center-to-center span of a {@link MoveType#LEAP} into {@code waypoint}: gap width + 1
     * (2..4). Read off the previous waypoint (leaps are cardinal, so the Chebyshev distance is
     * exact); when the leap is the path's first move the takeoff is our own start cell.
     */
    private double leapSpan(Waypoint waypoint) {
        int fromX;
        int fromZ;
        if (this.index > 0) {
            Waypoint previous = this.path.waypoints().get(this.index - 1);
            fromX = previous.x();
            fromZ = previous.z();
        } else {
            BlockPos feet = this.person.blockPosition();
            fromX = feet.getX();
            fromZ = feet.getZ();
        }
        return Math.max(Math.abs(waypoint.x() - fromX), Math.abs(waypoint.z() - fromZ));
    }

    private void advance(boolean wasLast) {
        this.stuckTicks = 0;
        if (!wasLast) {
            this.index++;
            return;
        }
        this.person.stopMoving();
        if (this.path.reachedGoal()) {
            this.path = null;
            this.state = State.ARRIVED;
        } else {
            // Finished a partial path: the goal was beyond the snapshot (or momentarily walled).
            // From this new position a fresh snapshot may reach further — long trips work as
            // successive legs of this.
            retryOrFail();
        }
    }

    private void retryOrFail() {
        if (this.repathsLeft-- > 0) {
            requestPath();
        } else {
            this.path = null;
            this.state = State.FAILED;
            this.person.stopMoving();
        }
    }

    private void emitPathParticles() {
        ServerLevel level = level();
        for (int i = this.index; i < this.path.waypoints().size(); i++) {
            Waypoint w = this.path.waypoints().get(i);
            level.sendParticles(ParticleTypes.END_ROD,
                    w.x() + 0.5, w.y() + 0.2, w.z() + 0.5, 1, 0.0, 0.0, 0.0, 0.0);
        }
        if (this.goal != null) {
            level.sendParticles(ParticleTypes.FLAME,
                    this.goal.getX() + 0.5, this.goal.getY() + 0.5, this.goal.getZ() + 0.5,
                    2, 0.0, 0.0, 0.0, 0.0);
        }
    }

    private ServerLevel level() {
        return (ServerLevel) this.person.level(); // only ever ticked server-side (serverAiStep)
    }
}
