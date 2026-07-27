package dev.luizloyola.autarkia.mod.brain;

import dev.luizloyola.autarkia.compat.sense.LevelProbe;
import dev.luizloyola.autarkia.core.brain.knowledge.HerdNoter;
import dev.luizloyola.autarkia.core.brain.knowledge.SenseEvent;
import dev.luizloyola.autarkia.core.brain.sense.Being;
import dev.luizloyola.autarkia.core.brain.sense.BeingEvent;
import dev.luizloyola.autarkia.core.brain.sense.BeingId;
import dev.luizloyola.autarkia.core.brain.sense.BeingReading;
import dev.luizloyola.autarkia.core.brain.sense.BeingSensorCore;
import dev.luizloyola.autarkia.core.brain.sense.BeingWorld;
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
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.NeutralMob;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.animal.fish.AbstractSchoolingFish;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.npc.villager.AbstractVillager;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ItemUseAnimation;
import net.minecraft.world.item.ProjectileWeaponItem;
import net.minecraft.world.item.TridentItem;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * The {@link BeingWorld} adapter over a live {@link Person} — the mod half of the being sense
 * (spec: {@code 2026-07-27-being-sense-design.md}): candidate queries, sight rays (multi-sample for
 * persons, one eye-to-center ray for creatures — decision: Luiz), the observable classifiers, the
 * journal narration. All judgment — cone, cadence, linger, the identification ladder, herds, the
 * ray budget — belongs to the pure {@link BeingSensorCore}.
 *
 * <p><b>Everything read here is a signal a watching player also gets.</b> Persons keep the full
 * classifier (poses, use-item animation, swing streaks, chest confirms, gaze dwells); creatures get
 * the thin tier-0 read plus their kind's sight-tier extras — weapon and armor, a mount, a baby's
 * size, a villager's outfit-visible profession, the SYNCED anger a neutral mob renders (reading it
 * is reading the body, not its brain).
 *
 * <p>Also mounts the {@link HerdNoter} beat, every {@link HerdNoter#NOTE_INTERVAL_TICKS}.
 */
public final class BeingSense {
    /** A swing right after a landed hit is a melee MISS, not mining; five seconds without
     *  hitting anyone clears it back to mining (decision: Luiz). */
    private static final int FIGHT_MARK_TICKS = 100;
    /** How long a place-mark keeps a body classified as BUILDING between placements. */
    private static final int BUILD_MARK_TICKS = 60;
    /** How tightly a drawn bow's view must align on them to read "aiming at" (cos ~15° — a
     *  tight cone, deliberately not exact; decision: Luiz). Instant, no dwell: a bow
     *  crossing you should alarm immediately. */
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

    /** The one species string every person-shaped body shares — seamlessness by construction. */
    public static final String PERSON_SPECIES = "person";

    private final Person person;
    private final BeingSensorCore sensor = new BeingSensorCore();
    private final BeingWorld world = new Oracle();

    /** Ticks a movement anchor must age before it is re-measured — the anti-flicker window. */
    private static final int MOVE_WINDOW_TICKS = 4;
    /** Consecutive swinging windows before a swing counts as MINING (one lone swing is just
     *  an interaction — caught live: opening a chest blipped "mining" on the way). */
    private static final int MINING_STREAK = 2;

    /** Ticks a gaze must dwell before it counts — a passing glance at a table (or at THEM)
     *  must not trigger (decision: Luiz: "only after looking for a determinate amount"). */
    private static final int GAZE_CONFIRM_TICKS = 20;
    /** Ticks a confirmed gaze survives a break — head-bobs must not untrigger it. */
    private static final int GAZE_GRACE_TICKS = 10;
    /** How tightly their view vector must align to count as looking (cos ~12°). */
    private static final double GAZE_ALIGN = 0.978;

    /** Live bodies by being-id, refreshed on every sweep; the oracle's reverse lookup. */
    private final Map<BeingId, LivingEntity> bodies = new HashMap<>();
    /** Each person-body's movement anchor and swing streak — the windowed observable signals. */
    private final Map<Integer, MoveTrack> moveTracks = new HashMap<>();
    /** Each person-body's gaze dwell state (on THEM, on a station) — the confirm-time filters. */
    private final Map<Integer, GazeTrack> gazeTracks = new HashMap<>();
    /** When each body's chest menu was last seen open — the station exit grace. */
    private final Map<Integer, Long> chestLastOpenAt = new HashMap<>();
    /** Ticks AT_CHEST survives past the last open-menu sighting: bridges the GUI-open inertia
     *  slide (caught live: a drift-beat flashed "moving" mid-chest) and bounds the exit. */
    private static final int CHEST_GRACE_TICKS = 15;

    private long lastHerdNoteAt = Long.MIN_VALUE / 2;

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
    private record MoveTrack(Vec3 pos, long time, Being.Locomotion locomotion, int swingStreak) {
    }

    public BeingSense(Person person) {
        this.person = person;
    }

    /** One sense tick, from {@link Person#serverAiStep()}; narrates events to the journal. */
    public void tick() {
        BlockPos feet = person.blockPosition();
        long now = person.level().getGameTime();
        Pos feetPos = new Pos(feet.getX(), feet.getY(), feet.getZ());
        List<BeingEvent> events = sensor.tick(feetPos, person.getYHeadRot(),
                person.getXRot(), now, world);
        for (BeingEvent event : events) {
            if (!narratable(event)) {
                continue;
            }
            journal(event);
            PersonId self = person.getPersonId();
            if (self != null && person.level().getServer() != null) {
                BeingViewer.onEvent(person.level().getServer(), self,
                        person.getName().getString(), person.getGender(), event);
            }
        }
        noteHerds(feetPos, now);
    }

    /** Everything currently perceived — what {@code Percepts.beings()} hands the brain. */
    public List<Being> beings() {
        return sensor.beings();
    }

    /**
     * The live body behind a perceived being, or {@code null} when none is tracked (herd aggregates
     * never have one).
     *
     * <p>For the DEBUG VIEW only, and only for a {@link Being.Awareness#SEEN} being: the sensor's
     * reading is a block cell on its attention cadence, and the body lets the client interpolate
     * between cells.
     *
     * <p>The map keeps a body while the sensor tracks the id, INCLUDING while the being is only
     * remembered — following it for a HEARD or REMEMBERED being would draw where that body is
     * rather than where they believe it to be. The caller gates on awareness; see
     * {@code DebugView.peers}.
     */
    public @Nullable LivingEntity bodyOf(BeingId id) {
        return bodies.get(id);
    }

    /**
     * The ear's push channel — called by the game-event listener (and the crack-knock hook)
     * when a living body makes a sound within hearing range. {@code heardAs}/{@code heardMoving}
     * are what the SOUND says (persons only — a creature's noises carry no occupation story);
     * {@code voice} is whether the sound NAMES the species (an idle call, a hurt cry, a
     * projectile launch — decision: Luiz). That is what climbs the identification ladder
     * through a wall.
     */
    public void heard(LivingEntity source, Being.Activity heardAs, Being.Locomotion heardMoving,
                      boolean voice) {
        BeingReading reading = read(source);
        if (reading != null) {
            bodies.put(reading.id(), source);
            sensor.heard(new BeingReading(reading.id(), reading.kind(), reading.species(),
                    reading.name(), reading.profession(), reading.herdAnimal(), reading.pos(),
                    reading.distance(), heardMoving, reading.sneaking(), reading.watching(),
                    reading.aimedAt(), reading.aggressive(), reading.gear(), heardAs),
                    person.level().getGameTime(), voice);
        }
    }

    // --- the herd-noting beat ----------------------------------------------------------------

    /** Folds perceived herd animals into durable knowledge every noter interval. */
    private void noteHerds(Pos feet, long now) {
        if (now - lastHerdNoteAt < HerdNoter.NOTE_INTERVAL_TICKS) {
            return;
        }
        PersonId self = person.getPersonId();
        if (self == null || person.level().getServer() == null) {
            return;
        }
        lastHerdNoteAt = now;
        KnowledgeData data = KnowledgeData.get(person.level().getServer());
        List<SenseEvent> events = HerdNoter.note(feet, sensor.beings(),
                data.registry().forPerson(self), now);
        data.setDirty();
        for (SenseEvent event : events) {
            person.journal().record(Category.SENSE,
                    event.type() == SenseEvent.Type.NOTED ? "noticed" : "forgot",
                    PoiSensor.describe(event));
            KnowledgeViewer.onEvent(person.level().getServer(), self,
                    person.getName().getString(), event);
        }
    }

    // --- the oracle --------------------------------------------------------------------------

    private final class Oracle implements BeingWorld {
        @Override
        public List<BeingReading> candidates() {
            double radius = BeingSensorCore.radius();
            double sneakRadius = radius * Config.get().d(Knob.PEERS_SNEAK_RANGE_MULT);
            List<LivingEntity> found = person.level().getEntitiesOfClass(
                    LivingEntity.class,
                    // A full cube: the vertical SHAPE of vision belongs to the cone band, not
                    // the query (caught by repro: a half-height box silently capped sight at
                    // ±12 blocks no matter what the band allowed).
                    person.getBoundingBox().inflate(radius, radius, radius),
                    e -> e != person && e.isAlive() && !(e instanceof ArmorStand)
                            && !(e instanceof Player p && p.isSpectator()));
            bodies.keySet().removeIf(id -> {
                LivingEntity body = bodies.get(id);
                return body == null || body.isRemoved();
            });
            List<BeingReading> readings = new ArrayList<>(found.size());
            for (LivingEntity body : found) {
                double distance = body.distanceTo(person);
                if (distance > radius || (body.isCrouching() && distance > sneakRadius)) {
                    continue; // sneaking shrinks how far away you get noticed (decision: Luiz)
                }
                BeingReading reading = read(body);
                if (reading != null) {
                    bodies.put(reading.id(), body);
                    readings.add(reading);
                }
            }
            return readings;
        }

        @Override
        public @Nullable BeingReading reading(BeingId id) {
            LivingEntity body = bodies.get(id);
            if (body == null || body.isRemoved() || !body.isAlive()
                    || body.level() != person.level()) {
                return null;
            }
            double radius = BeingSensorCore.radius();
            double distance = body.distanceTo(person);
            if (distance > radius
                    || (body.isCrouching()
                            && distance > radius * Config.get().d(Knob.PEERS_SNEAK_RANGE_MULT))) {
                return null;
            }
            return read(body);
        }

        @Override
        public boolean inSight(BeingId id) {
            LivingEntity body = bodies.get(id);
            if (body == null || body.isRemoved()) {
                return false;
            }
            // Persons get the multi-sample rays (peeking over a wall works on people);
            // creatures get one eye-to-center ray (decision: Luiz — farms become a lot faster).
            return personShaped(body)
                    ? LevelProbe.bodyVisible(person.level(), person.getEyePosition(), body)
                    : LevelProbe.centerVisible(person.level(), person.getEyePosition(), body);
        }
    }

    private static boolean personShaped(LivingEntity body) {
        return body instanceof Person || body instanceof Player;
    }

    // --- the readers -------------------------------------------------------------------------

    /** One full observation of a body, or null while its identity hasn't resolved yet. */
    private @Nullable BeingReading read(LivingEntity body) {
        return personShaped(body) ? readPerson(body) : readCreature(body);
    }

    /** The thin tier-0 creature read plus its kind's sight-tier extras — no classifier maps. */
    private BeingReading readCreature(LivingEntity body) {
        BeingId id = BeingId.of(body.getUUID());
        Being.Kind kind = kindOf(body);
        boolean aggressive = kind == Being.Kind.MONSTER
                || (body instanceof NeutralMob neutral && neutral.isAngry());
        String name = body.hasCustomName() ? body.getCustomName().getString() : "";
        BlockPos cell = body.blockPosition();
        return new BeingReading(id, kind, speciesOf(body), name, professionOf(body),
                body instanceof Animal || body instanceof AbstractSchoolingFish,
                new Pos(cell.getX(), cell.getY(), cell.getZ()), body.distanceTo(person),
                Being.Locomotion.STILL, false, false, false, aggressive, gearOf(body),
                Being.Activity.IDLE);
    }

    /** The classification, from the game's own types — NeutralMob first: enderman and
     *  zombified piglin are both Enemy and NeutralMob, and neutral is the truer story. */
    private static Being.Kind kindOf(LivingEntity body) {
        if (body instanceof NeutralMob) {
            return Being.Kind.NEUTRAL;
        }
        if (body instanceof Enemy) {
            return Being.Kind.MONSTER;
        }
        if (body instanceof AbstractVillager) {
            return Being.Kind.VILLAGER;
        }
        return Being.Kind.PASSIVE;
    }

    /** Registry path, namespace-qualified only when not vanilla — {@code Being.species}. */
    private static String speciesOf(LivingEntity body) {
        Identifier key = BuiltInRegistries.ENTITY_TYPE.getKey(body.getType());
        return key.getNamespace().equals("minecraft") ? key.getPath() : key.toString();
    }

    /** The outfit-visible profession, or null — villagers only, "none" reads as null. */
    private static @Nullable String professionOf(LivingEntity body) {
        if (!(body instanceof Villager villager)) {
            return null;
        }
        String path = villager.getVillagerData().profession().unwrapKey()
                .map(k -> k.identifier().getPath()).orElse("none");
        return path.equals("none") ? null : path;
    }

    /** The visible equipment story — the danger modifiers (decision: Luiz). */
    private static Being.Gear gearOf(LivingEntity body) {
        ItemStack held = body.getMainHandItem();
        boolean ranged = held.getItem() instanceof ProjectileWeaponItem
                || held.getItem() instanceof TridentItem;
        boolean melee = !ranged && !held.isEmpty() && held.has(DataComponents.WEAPON);
        boolean armored = !body.getItemBySlot(EquipmentSlot.HEAD).isEmpty()
                || !body.getItemBySlot(EquipmentSlot.CHEST).isEmpty()
                || !body.getItemBySlot(EquipmentSlot.LEGS).isEmpty()
                || !body.getItemBySlot(EquipmentSlot.FEET).isEmpty();
        return new Being.Gear(melee, ranged, armored, body.isPassenger(), body.isBaby());
    }

    /** The full person observation — the peer-sensor classifier. */
    private @Nullable BeingReading readPerson(LivingEntity body) {
        PersonId personId = body instanceof Person other
                ? other.getPersonId()
                : PersonId.of(body.getUUID()); // a player's account uuid is their person-identity
        if (personId == null) {
            return null;
        }
        BeingId id = BeingId.of(personId);
        Vec3 at = body.position();
        long now = person.level().getGameTime();
        MoveTrack before = moveTracks.get(body.getId());
        Being.Locomotion locomotion;
        int streak;
        if (before == null) {
            locomotion = Being.Locomotion.STILL;
            streak = body.swinging ? 1 : 0;
            moveTracks.put(body.getId(), new MoveTrack(at, now, locomotion, streak));
        } else if (now - before.time() < MOVE_WINDOW_TICKS) {
            locomotion = before.locomotion(); // inside the window: reuse verdict, KEEP anchor
            streak = before.swingStreak();
        } else {
            double speed = before.pos().distanceTo(at) / (now - before.time());
            locomotion = speed > SPRINT_SPEED_PER_TICK ? Being.Locomotion.SPRINTING
                    : speed > MOVE_SPEED_PER_TICK ? Being.Locomotion.WALKING
                    : Being.Locomotion.STILL;
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
        Being.Activity activity = classify(body, streak, atTable, locomotion);
        boolean aimedAt = activity == Being.Activity.AIMING && gazeOnHer(body, AIM_ALIGN);
        return new BeingReading(id, Being.Kind.PERSON, PERSON_SPECIES,
                body.getName().getString(), null, false,
                new Pos(cell.getX(), cell.getY(), cell.getZ()),
                body.distanceTo(person), locomotion, body.isCrouching(), watching, aimedAt,
                false, Being.Gear.NONE, activity);
    }

    /** Whether their gaze is ON them within {@code minDot} — watching's signal, aiming's cone. */
    private boolean gazeOnHer(LivingEntity body, double minDot) {
        Vec3 toHer = person.getEyePosition().subtract(body.getEyePosition());
        double length = toHer.length();
        return length > 0.5 && toHer.scale(1.0 / length).dot(body.getViewVector(1.0F)) >= minDot;
    }

    /** The occupation ladder (ARMS/ATTENTION only — the legs are their own axis now). */
    private Being.Activity classify(LivingEntity body, int swingStreak, boolean atTable,
                                    Being.Locomotion locomotion) {
        if (body.isSleeping()) {
            return Being.Activity.SLEEPING;
        }
        if (body.isUsingItem()) {
            ItemUseAnimation animation = body.getUseItem().getUseAnimation();
            switch (animation) {
                case EAT: return Being.Activity.EATING;
                case DRINK: return Being.Activity.DRINKING;
                case BLOCK: return Being.Activity.BLOCKING;
                case BOW: case CROSSBOW: case SPEAR: return Being.Activity.AIMING;
                default: break; // brush, horn, ... — no vocabulary for it yet, read on
            }
        }
        long now = person.level().getGameTime();
        if (body instanceof ServerPlayer player && player.containerMenu instanceof ChestMenu) {
            // The one station the world ANNOUNCES: the lid visibly opens, so the server-side
            // menu check merely confirms what is already watchable (decision: Luiz).
            chestLastOpenAt.put(body.getId(), now);
            return Being.Activity.AT_CHEST;
        }
        if (now - chestLastOpenAt.getOrDefault(body.getId(), Long.MIN_VALUE / 2) <= CHEST_GRACE_TICKS) {
            return Being.Activity.AT_CHEST; // just stepped back from the lid — the exit grace
        }
        if (body.swinging && recentlyDealtDamage(body)) {
            return Being.Activity.FIGHTING; // a landed hit confirms instantly, and lingers:
                                            // the next swings are misses, not mining
        }
        if (PlaceMarks.placedWithin(body.getUUID(), person.level().getGameTime(), BUILD_MARK_TICKS)) {
            return Being.Activity.BUILDING; // blocks landing tell the swing apart from mining
        }
        if (body.swinging && swingStreak >= MINING_STREAK) {
            return Being.Activity.MINING; // sustained arm work; a lone swing is an interaction
        }
        if (atTable && locomotion == Being.Locomotion.STILL) {
            // Pure inference: STANDING at a table with the gaze dwelling on it — wrong the way a
            // human guess is, but never off a passing glance (the dwell filter owns that) and never
            // while walking past.
            return Being.Activity.AT_CRAFTING;
        }
        return Being.Activity.IDLE;
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

    /**
     * The kind gate (decision recorded in the spec): persons — and the not-yet-made-out
     * somethings, which may well BE persons — narrate every axis flip; identified creatures
     * narrate only spotted / recognized / lost, or a chase's approaching-flips would drown
     * the journal and the viewer chat. The full state is always on demand in
     * {@code /autarkia peers}.
     */
    private static boolean narratable(BeingEvent event) {
        boolean chatty = event.being().kind() == Being.Kind.PERSON
                || event.being().kind() == Being.Kind.UNKNOWN;
        return chatty || event.type() != BeingEvent.Type.READING_CHANGED;
    }

    private void journal(BeingEvent event) {
        Being being = event.being();
        String what = switch (event.type()) {
            case SPOTTED -> "spotted " + being.knownAs() + " (" + describe(being) + ")";
            case LOST -> "lost track of " + being.knownAs();
            case READING_CHANGED -> being.knownAs() + " now " + describe(being);
            case RECOGNIZED -> "recognized " + being.knownAs() + " — the "
                    + (event.was().identified() == Being.Identified.NONE
                            ? "someone" : event.was().knownAs())
                    + " " + person.getGender().subjectPronoun() + "'d heard";
        };
        person.journal().record(Category.SENSE, "peer", what);
    }

    private String describe(Being being) {
        return being.tell(person.getGender().objectPronoun())
                + (being.awareness() == Being.Awareness.HEARD ? ", heard" : "");
    }
}
