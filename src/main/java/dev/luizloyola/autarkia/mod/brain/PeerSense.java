package dev.luizloyola.autarkia.mod.brain;

import dev.luizloyola.autarkia.compat.sense.LevelProbe;
import dev.luizloyola.autarkia.core.brain.sense.Peer;
import dev.luizloyola.autarkia.core.brain.sense.PeerEvent;
import dev.luizloyola.autarkia.core.brain.sense.PeerReading;
import dev.luizloyola.autarkia.core.brain.sense.PeerSensorCore;
import dev.luizloyola.autarkia.core.brain.sense.PeerWorld;
import dev.luizloyola.autarkia.core.brain.sense.Pos;
import dev.luizloyola.autarkia.core.config.Config;
import dev.luizloyola.autarkia.core.config.Knob;
import dev.luizloyola.autarkia.core.log.Category;
import dev.luizloyola.autarkia.core.person.PersonId;
import dev.luizloyola.autarkia.mod.entity.Person;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.item.ItemUseAnimation;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * The {@link PeerWorld} adapter over a live {@link Person} — the mod half of the people sense
 * (spec: {@code 2026-07-26-peer-sensor-design.md}): candidate queries at the sneak-shrunk radius,
 * eye-to-hitbox sight rays ({@link LevelProbe#bodyVisible}), the activity classifier and the journal
 * narration. {@link PeerSensorCore} owns all judgment (cone, cadence, linger).
 *
 * <p><b>The classifier reads bodies, not brains.</b> Every signal is one a watching player also
 * gets: poses, the use-item animation, the swing plus a recently-dealt-damage mark, the visibly
 * opening chest (menu-confirmed), and the facing-a-crafting-table ASSUMPTION, wrong sometimes
 * the way a human guess is.
 */
public final class PeerSense {
    /** A swing right after a landed hit is a melee MISS, not mining; five seconds without
     *  hitting anyone clears it back to mining (decision: Luiz). */
    private static final int FIGHT_MARK_TICKS = 100;
    /** How long a place-mark keeps a body classified as BUILDING between placements. */
    private static final int BUILD_MARK_TICKS = 60;
    /** How tightly a drawn bow's view must align on her to read "aiming at" (cos ~15°,
     *  not exact). No dwell: a bow crossing you alarms immediately. */
    private static final double AIM_ALIGN = 0.966;
    /**
     * Blocks-per-tick above which feet count as moving — walking is ~0.21, sneak-walking ~0.065,
     * standing jitter ~0. A SPEED, not a displacement: readings arrive on an irregular cadence, so
     * a raw between-readings delta flickered moving/idle.
     */
    private static final double MOVE_SPEED_PER_TICK = 0.04;
    /** Blocks-per-tick above which walking reads as sprinting (a sprint is ~0.28). */
    private static final double SPRINT_SPEED_PER_TICK = 0.25;
    /** How far ahead (blocks) the crafting-table assumption looks along the gaze —
     *  generous on purpose ("allow further from the crafting table", decision: Luiz). */
    private static final double STATION_REACH = 4.0;

    private final Person person;
    private final PeerSensorCore sensor = new PeerSensorCore();
    private final PeerWorld world = new Oracle();

    /** Ticks a movement anchor must age before it is re-measured — the anti-flicker window. */
    private static final int MOVE_WINDOW_TICKS = 4;
    /** Consecutive swinging windows before a swing counts as MINING (one lone swing is just
     *  an interaction — caught live: opening a chest blipped "mining" on the way). */
    private static final int MINING_STREAK = 2;

    /** Ticks a gaze must dwell before it counts — a passing glance at a table (or at HER)
     *  must not trigger. */
    private static final int GAZE_CONFIRM_TICKS = 20;
    /** Ticks a confirmed gaze survives a break — head-bobs must not untrigger it. */
    private static final int GAZE_GRACE_TICKS = 10;
    /** How tightly their view vector must align to count as looking (cos ~12°). */
    private static final double GAZE_ALIGN = 0.978;

    /** Live bodies by person-id, refreshed on every sweep; the oracle's reverse lookup. */
    private final Map<PersonId, LivingEntity> bodies = new HashMap<>();
    /** Each body's movement anchor and swing streak — the windowed observable signals. */
    private final Map<Integer, MoveTrack> moveTracks = new HashMap<>();
    /** Each body's gaze dwell state (on HER, on a station) — the confirm-time filters. */
    private final Map<Integer, GazeTrack> gazeTracks = new HashMap<>();
    /** When each body's chest menu was last seen open — the station exit grace. */
    private final Map<Integer, Long> chestLastOpenAt = new HashMap<>();
    /** Ticks AT_CHEST survives past the last open-menu sighting: bridges the GUI-open inertia
     *  slide (caught live: a drift-beat flashed "moving" mid-chest) and bounds the exit. */
    private static final int CHEST_GRACE_TICKS = 15;

    /** Dwell bookkeeping for one body's gaze targets; sentinels are half-range ("never"). */
    private static final class GazeTrack {
        long meSince = Long.MIN_VALUE / 2;
        long meLastRaw = Long.MIN_VALUE / 2;
        long tableSince = Long.MIN_VALUE / 2;
        long tableLastRaw = Long.MIN_VALUE / 2;
    }

    /**
     * One body's anchored sample. The anchor advances only every {@link #MOVE_WINDOW_TICKS}, so
     * speed is measured over a real window; re-anchoring on every read flickered moving/idle off
     * single-tick packet hiccups.
     */
    private record MoveTrack(Vec3 pos, long time, Peer.Locomotion locomotion, int swingStreak) {
    }

    public PeerSense(Person person) {
        this.person = person;
    }

    /** One sense tick, from {@link Person#serverAiStep()}; narrates events to the journal. */
    public void tick() {
        BlockPos feet = person.blockPosition();
        long now = person.level().getGameTime();
        List<PeerEvent> events = sensor.tick(
                new Pos(feet.getX(), feet.getY(), feet.getZ()), person.getYHeadRot(),
                person.getXRot(), now, world);
        for (PeerEvent event : events) {
            journal(event);
            PersonId self = person.getPersonId();
            if (self != null && person.level().getServer() != null) {
                PeerViewer.onEvent(person.level().getServer(), self,
                        person.getName().getString(), person.getGender().objectPronoun(), event);
            }
        }
    }

    /** Everyone currently perceived — what {@code Percepts.peers()} hands the brain. */
    public List<Peer> peers() {
        return sensor.peers();
    }

    /**
     * The live body behind a perceived peer, or {@code null} when none is tracked.
     *
     * <p>DEBUG VIEW only, and only for a {@link Peer.Awareness#SEEN} peer: the sensor's cell
     * reading snaps from cell to cell on its attention cadence, and the body lets the client
     * interpolate instead. The map keeps a body while the peer is only remembered, so following it
     * for a HEARD or REMEMBERED peer would draw where the body is, not where she believes it to be.
     * The caller gates on awareness; see {@code DebugView.peers}.
     */
    public @Nullable LivingEntity bodyOf(PersonId id) {
        return bodies.get(id);
    }

    /**
     * The ear's push channel — called by the game-event listener and the crack-knock hook.
     * {@code heardAs} is what the SOUND says they're doing; the ear never runs the visual
     * classifier, and the sensor keeps this activity while the ear is the only live channel.
     */
    public void heard(LivingEntity source, Peer.Activity heardAs, Peer.Locomotion heardMoving) {
        PeerReading reading = read(source);
        if (reading != null) {
            bodies.put(reading.id(), source);
            sensor.heard(new PeerReading(reading.id(), reading.name(), reading.pos(),
                    reading.distance(), heardMoving, reading.sneaking(), reading.watching(),
                    reading.aimedAt(), heardAs), person.level().getGameTime());
        }
    }

    // --- the oracle --------------------------------------------------------------------------

    private final class Oracle implements PeerWorld {
        @Override
        public List<PeerReading> candidates() {
            double radius = PeerSensorCore.radius();
            double sneakRadius = radius * Config.get().d(Knob.PEERS_SNEAK_RANGE_MULT);
            List<LivingEntity> found = person.level().getEntitiesOfClass(
                    LivingEntity.class,
                    person.getBoundingBox().inflate(radius, radius / 2.0, radius),
                    e -> e != person && e.isAlive()
                            && (e instanceof Person || (e instanceof Player p && !p.isSpectator())));
            bodies.keySet().removeIf(id -> {
                LivingEntity body = bodies.get(id);
                return body == null || body.isRemoved();
            });
            List<PeerReading> readings = new ArrayList<>(found.size());
            for (LivingEntity body : found) {
                double distance = body.distanceTo(person);
                if (distance > radius || (body.isCrouching() && distance > sneakRadius)) {
                    continue; // sneaking shrinks how far away you get noticed (decision: Luiz)
                }
                PeerReading reading = read(body);
                if (reading != null) {
                    bodies.put(reading.id(), body);
                    readings.add(reading);
                }
            }
            return readings;
        }

        @Override
        public @Nullable PeerReading reading(PersonId id) {
            LivingEntity body = bodies.get(id);
            if (body == null || body.isRemoved() || !body.isAlive()
                    || body.level() != person.level()) {
                return null;
            }
            double radius = PeerSensorCore.radius();
            double distance = body.distanceTo(person);
            if (distance > radius
                    || (body.isCrouching()
                            && distance > radius * Config.get().d(Knob.PEERS_SNEAK_RANGE_MULT))) {
                return null;
            }
            return read(body);
        }

        @Override
        public boolean inSight(PersonId id) {
            LivingEntity body = bodies.get(id);
            return body != null && !body.isRemoved()
                    && LevelProbe.bodyVisible(person.level(), person.getEyePosition(), body);
        }
    }

    // --- the classifier ----------------------------------------------------------------------

    /** One full observation of a body, or null while its identity hasn't resolved yet. */
    private @Nullable PeerReading read(LivingEntity body) {
        PersonId id = body instanceof Person other
                ? other.getPersonId()
                : PersonId.of(body.getUUID()); // a player's account uuid is their person-identity
        if (id == null) {
            return null;
        }
        Vec3 at = body.position();
        long now = person.level().getGameTime();
        MoveTrack before = moveTracks.get(body.getId());
        Peer.Locomotion locomotion;
        int streak;
        if (before == null) {
            locomotion = Peer.Locomotion.STILL;
            streak = body.swinging ? 1 : 0;
            moveTracks.put(body.getId(), new MoveTrack(at, now, locomotion, streak));
        } else if (now - before.time() < MOVE_WINDOW_TICKS) {
            locomotion = before.locomotion(); // inside the window: reuse verdict, KEEP anchor
            streak = before.swingStreak();
        } else {
            double speed = before.pos().distanceTo(at) / (now - before.time());
            locomotion = speed > SPRINT_SPEED_PER_TICK ? Peer.Locomotion.SPRINTING
                    : speed > MOVE_SPEED_PER_TICK ? Peer.Locomotion.WALKING
                    : Peer.Locomotion.STILL;
            streak = body.swinging ? before.swingStreak() + 1 : 0;
            moveTracks.put(body.getId(), new MoveTrack(at, now, locomotion, streak));
        }
        GazeTrack gaze = gazeTracks.computeIfAbsent(body.getId(), k -> new GazeTrack());
        if (gazeOnHer(body, GAZE_ALIGN)) {
            if (now - gaze.meLastRaw > GAZE_GRACE_TICKS) {
                gaze.meSince = now; // the chain broke — dwell starts over
            }
            gaze.meLastRaw = now;
        }
        boolean watching = now - gaze.meLastRaw <= GAZE_GRACE_TICKS
                && gaze.meLastRaw - gaze.meSince >= GAZE_CONFIRM_TICKS;
        if (facingCraftingTable(body)) {
            if (now - gaze.tableLastRaw > GAZE_GRACE_TICKS) {
                gaze.tableSince = now;
            }
            gaze.tableLastRaw = now;
        }
        boolean atTable = now - gaze.tableLastRaw <= GAZE_GRACE_TICKS
                && gaze.tableLastRaw - gaze.tableSince >= GAZE_CONFIRM_TICKS;
        BlockPos cell = body.blockPosition();
        Peer.Activity activity = classify(body, streak, atTable, locomotion);
        boolean aimedAt = activity == Peer.Activity.AIMING && gazeOnHer(body, AIM_ALIGN);
        return new PeerReading(id, body.getName().getString(),
                new Pos(cell.getX(), cell.getY(), cell.getZ()),
                body.distanceTo(person), locomotion, body.isCrouching(), watching, aimedAt,
                activity);
    }

    /** Whether their gaze is ON her within {@code minDot} — watching's raw signal, aiming's cone. */
    private boolean gazeOnHer(LivingEntity body, double minDot) {
        Vec3 toHer = person.getEyePosition().subtract(body.getEyePosition());
        double length = toHer.length();
        return length > 0.5 && toHer.scale(1.0 / length).dot(body.getViewVector(1.0F)) >= minDot;
    }

    /** The occupation ladder (ARMS/ATTENTION only — the legs are their own axis now). */
    private Peer.Activity classify(LivingEntity body, int swingStreak, boolean atTable,
                                   Peer.Locomotion locomotion) {
        if (body.isSleeping()) {
            return Peer.Activity.SLEEPING;
        }
        if (body.isUsingItem()) {
            ItemUseAnimation animation = body.getUseItem().getUseAnimation();
            switch (animation) {
                case EAT: return Peer.Activity.EATING;
                case DRINK: return Peer.Activity.DRINKING;
                case BLOCK: return Peer.Activity.BLOCKING;
                case BOW: case CROSSBOW: case SPEAR: return Peer.Activity.AIMING;
                default: break; // brush, horn, ... — no vocabulary for it yet, read on
            }
        }
        long now = person.level().getGameTime();
        if (body instanceof ServerPlayer player && player.containerMenu instanceof ChestMenu) {
            // The one station the world ANNOUNCES: the lid visibly opens, so the server-side
            // menu check merely confirms what is already watchable (decision: Luiz).
            chestLastOpenAt.put(body.getId(), now);
            return Peer.Activity.AT_CHEST;
        }
        if (now - chestLastOpenAt.getOrDefault(body.getId(), Long.MIN_VALUE / 2) <= CHEST_GRACE_TICKS) {
            return Peer.Activity.AT_CHEST; // just stepped back from the lid — the exit grace
        }
        if (body.swinging && recentlyDealtDamage(body)) {
            return Peer.Activity.FIGHTING; // a landed hit confirms instantly, and lingers:
                                           // the next swings are misses, not mining
        }
        if (PlaceMarks.placedWithin(body.getUUID(), person.level().getGameTime(), BUILD_MARK_TICKS)) {
            return Peer.Activity.BUILDING; // blocks landing tell the swing apart from mining
        }
        if (body.swinging && swingStreak >= MINING_STREAK) {
            return Peer.Activity.MINING; // sustained arm work; a lone swing is an interaction
        }
        if (atTable && locomotion == Peer.Locomotion.STILL) {
            // Pure inference: STANDING at a table with the gaze dwelling on it — wrong the way a
            // human guess is, but never off a passing glance (the dwell filter owns that) and never
            // while walking past.
            return Peer.Activity.AT_CRAFTING;
        }
        return Peer.Activity.IDLE;
    }

    private boolean recentlyDealtDamage(LivingEntity body) {
        return DamageMarks.dealtWithin(body.getUUID(), person.level().getGameTime(), FIGHT_MARK_TICKS);
    }

    private boolean facingCraftingTable(LivingEntity body) {
        Vec3 eye = body.getEyePosition();
        Vec3 view = body.getViewVector(1.0F);
        // Half-block strides: a full-block step straddles a table at common gaze pitches
        // (caught live: a Person pitched 35° at a table sampled the cell above, then the
        // cell beyond — never the table).
        for (double reach = 1.0; reach <= STATION_REACH; reach += 0.5) {
            BlockPos cell = BlockPos.containing(eye.add(view.scale(reach)));
            if (person.level().isLoaded(cell)
                    && person.level().getBlockState(cell).is(Blocks.CRAFTING_TABLE)) {
                return true;
            }
        }
        return false;
    }

    // --- journal -----------------------------------------------------------------------------

    private void journal(PeerEvent event) {
        String what = switch (event.type()) {
            case SPOTTED -> "spotted " + event.peer().knownAs()
                    + " (" + describe(event.peer()) + ")";
            case LOST -> "lost track of " + event.peer().knownAs();
            case READING_CHANGED -> event.peer().knownAs() + " now "
                    + describe(event.peer()) + " (was " + describe(event.was()) + ")";
            case RECOGNIZED -> "recognized " + event.peer().name() + " — the someone she'd heard";
        };
        person.journal().record(Category.SENSE, "peer", what);
    }

    private String describe(Peer peer) {
        return peer.tell(person.getGender().objectPronoun())
                + (peer.awareness() == Peer.Awareness.HEARD ? ", heard" : "");
    }
}
