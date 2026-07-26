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
    /** How long a dealt-damage mark keeps a swinger classified as FIGHTING. */
    private static final int FIGHT_MARK_TICKS = 20;
    /** Feet displacement between two consecutive readings that counts as moving. */
    private static final double MOVE_EPSILON_SQ = 0.0025;
    /** How far ahead (blocks) the crafting-table assumption looks along the gaze. */
    private static final double STATION_REACH = 2.5;

    private final Person person;
    private final PeerSensorCore sensor = new PeerSensorCore();
    private final PeerWorld world = new Oracle();

    /** Live bodies by person-id, refreshed on every sweep; the oracle's reverse lookup. */
    private final Map<PersonId, LivingEntity> bodies = new HashMap<>();
    /** Each body's feet at its previous reading — the movement signal, observable-only. */
    private final Map<Integer, Vec3> lastFeet = new HashMap<>();

    public PeerSense(Person person) {
        this.person = person;
    }

    /** One sense tick, from {@link Person#serverAiStep()}; narrates events to the journal. */
    public void tick() {
        BlockPos feet = person.blockPosition();
        long now = person.level().getGameTime();
        List<PeerEvent> events = sensor.tick(
                new Pos(feet.getX(), feet.getY(), feet.getZ()), person.getYHeadRot(), now, world);
        for (PeerEvent event : events) {
            journal(event);
        }
    }

    /** Everyone currently perceived — what {@code Percepts.peers()} hands the brain. */
    public List<Peer> peers() {
        return sensor.peers();
    }

    /**
     * The ear's push channel — called by the game-event listener when a person-shaped body
     * makes a sound within hearing range. Sneak-silence is enforced by the caller.
     */
    public void heard(LivingEntity source) {
        PeerReading reading = read(source);
        if (reading != null) {
            bodies.put(reading.id(), source);
            sensor.heard(reading, person.level().getGameTime());
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
        Vec3 before = lastFeet.put(body.getId(), at);
        boolean moving = before != null && before.distanceToSqr(at) > MOVE_EPSILON_SQ;
        BlockPos cell = body.blockPosition();
        return new PeerReading(id, body.getName().getString(),
                new Pos(cell.getX(), cell.getY(), cell.getZ()),
                body.distanceTo(person), body.isCrouching(), classify(body, moving));
    }

    /** The observable-activity ladder — coarsest, most certain signals first. */
    private Peer.Activity classify(LivingEntity body, boolean moving) {
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
        if (body instanceof ServerPlayer player && player.containerMenu instanceof ChestMenu) {
            // The one station the world ANNOUNCES: the lid visibly opens, so the server-side
            // menu check merely confirms what is already watchable (decision: Luiz).
            return Peer.Activity.AT_CHEST;
        }
        if (body.swinging) {
            return recentlyDealtDamage(body) ? Peer.Activity.FIGHTING : Peer.Activity.MINING;
        }
        if (moving) {
            return Peer.Activity.MOVING;
        }
        if (facingCraftingTable(body)) {
            // Pure inference: standing at a table, facing it — wrong the way a human guess is.
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
            case SPOTTED -> "spotted " + event.peer().name()
                    + " (" + describe(event.peer()) + ")";
            case LOST -> "lost track of " + event.peer().name();
            case ACTIVITY_CHANGED -> event.peer().name() + " now "
                    + describe(event.peer()) + " (was "
                    + event.was().name().toLowerCase(Locale.ROOT) + ")";
        };
        person.journal().record(Category.SENSE, "peer", what);
    }

    private static String describe(Peer peer) {
        return peer.activity().name().toLowerCase(Locale.ROOT)
                + (peer.sneaking() ? ", sneaking" : "")
                + (peer.awareness() == Peer.Awareness.HEARD ? ", heard" : "");
    }
}
