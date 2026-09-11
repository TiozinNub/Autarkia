package dev.luizloyola.autarkia.mod.entity;

import dev.luizloyola.anima.core.agent.AgentModifiers;
import dev.luizloyola.anima.core.agent.AgentProfile;
import dev.luizloyola.anima.core.agent.ModifiedProfile;
import dev.luizloyola.anima.core.brain.sense.DangerTable;
import dev.luizloyola.anima.mod.client.AgentContactsClient;
import dev.luizloyola.anima.compat.inv.Inventories;
import dev.luizloyola.anima.compat.inv.ItemStacks;
import dev.luizloyola.anima.core.inv.ArmorType;
import dev.luizloyola.anima.core.inv.Inventory;
import dev.luizloyola.anima.core.log.Category;
import dev.luizloyola.anima.core.nav.Gait;
import dev.luizloyola.anima.core.log.AgentJournal;
import dev.luizloyola.autarkia.core.config.AutarkiaConfig;
import dev.luizloyola.anima.core.appearance.Recipe;
import dev.luizloyola.autarkia.core.person.Appearance;
import dev.luizloyola.autarkia.core.person.AppearanceComposer;
import dev.luizloyola.autarkia.core.person.Look;
import dev.luizloyola.autarkia.core.person.Gender;
import dev.luizloyola.autarkia.core.person.ModelType;
import dev.luizloyola.anima.core.agent.Metabolism;
import dev.luizloyola.anima.compat.agent.BodyEffects;
import dev.luizloyola.anima.core.agent.need.BreathNeed;
import dev.luizloyola.anima.core.agent.need.Company;
import dev.luizloyola.anima.core.agent.need.FoodNeed;
import dev.luizloyola.anima.core.agent.need.Gauge;
import dev.luizloyola.anima.core.agent.need.NeedKind;
import dev.luizloyola.anima.core.agent.need.NeedLevel;
import dev.luizloyola.anima.core.agent.need.Needs;
import dev.luizloyola.anima.core.agent.need.Vigor;
import dev.luizloyola.anima.core.agent.AgentId;
import dev.luizloyola.anima.core.brain.sense.Being;
import dev.luizloyola.anima.core.brain.sense.Setbacks;
import dev.luizloyola.anima.mod.social.ContactData;
import dev.luizloyola.autarkia.core.person.PersonDanger;
import dev.luizloyola.autarkia.core.person.PersonIdentity;
import dev.luizloyola.autarkia.core.person.PersonSpecies;
import dev.luizloyola.autarkia.mod.AutarkiaMod;
import dev.luizloyola.anima.mod.brain.BrainDriver;
import dev.luizloyola.anima.core.brain.board.WorkSource;
import dev.luizloyola.anima.core.social.PartyId;
import dev.luizloyola.anima.mod.social.PartyData;
import dev.luizloyola.autarkia.core.board.ComposedBoards;
import dev.luizloyola.autarkia.core.board.KeepStocked;
import dev.luizloyola.autarkia.core.board.PersonalBoard;
import dev.luizloyola.autarkia.core.board.StandingWants;
import dev.luizloyola.autarkia.core.board.StowSurplus;
import dev.luizloyola.autarkia.mod.board.PartyBoards;
import dev.luizloyola.anima.mod.brain.AgentBlockBreaker;
import dev.luizloyola.anima.mod.brain.AgentLeaner;
import dev.luizloyola.anima.mod.brain.AgentRiser;
import dev.luizloyola.anima.mod.brain.PoiSensor;
import dev.luizloyola.autarkia.mod.inv.PersonContainer;
import dev.luizloyola.autarkia.mod.inv.PersonInventoryMenu;
import dev.luizloyola.anima.mod.log.Journals;
import dev.luizloyola.anima.mod.nav.Navigator;
import dev.luizloyola.anima.mod.nav.Swimmer;
import dev.luizloyola.autarkia.mod.brain.AutarkiaTasks;
import dev.luizloyola.autarkia.mod.person.PersonAppearance;
import dev.luizloyola.autarkia.mod.person.PersonDirectory;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.chat.Component;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Avatar;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.decoration.Mannequin;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ResolvableProfile;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.phys.Vec3;
import dev.luizloyola.anima.core.agent.Pronouns;
import dev.luizloyola.anima.core.brain.act.Gazer;
import dev.luizloyola.anima.mod.body.AgentAttributes;
import dev.luizloyola.anima.mod.body.AgentBody;
import dev.luizloyola.anima.mod.body.Gaze;
import com.mojang.serialization.Codec;
import dev.luizloyola.anima.mod.body.Modifiers;
import dev.luizloyola.anima.mod.brain.BrainState;
import dev.luizloyola.anima.mod.brain.SenseState;
import dev.luizloyola.anima.mod.log.Journals;
import dev.luizloyola.anima.mod.identity.Burial;
import org.jspecify.annotations.Nullable;

/**
 * A player-shaped autonomous NPC. Extends {@link Avatar} so it renders like a player without
 * inheriting the immovable "decoration" behaviour of {@link Mannequin}.
 *
 * <p>Anima's first {@link AgentBody}: the mind belongs to the library and knows nothing about
 * Persons; this class adds only what it means to be a <em>person</em>.
 */
public class Person extends Avatar implements AgentBody {
    /**
     * External identity synced to clients for rendering, as one encoded {@link Appearance}: gender,
     * body model and look together. Projected from the person's record in the
     * {@link PersonDirectory}, which stays the source of truth; the name is not synced.
     *
     * <p><b>One field rather than three.</b> Each synced field is a protocol change that
     * <em>cannot be hot-swapped in</em> — {@link SynchedEntityData#defineId} runs in a static
     * initialiser, and a redefinition never re-runs one. The encoding is
     * {@link Appearance#encode()}, the same string the store writes, so there is one representation
     * rather than two that can drift.
     *
     * <p>⚠️ Synched data is <em>not</em> entity NBT, so this never shows up in
     * {@code /data get entity}; {@code /autarkia whois} reads the store's copy back.
     */
    private static final EntityDataAccessor<String> DATA_APPEARANCE =
            SynchedEntityData.defineId(Person.class, EntityDataSerializers.STRING);

    /**
     * This person's identity handle ({@link AgentId}) as a UUID string, synced so the client can
     * match a rendered entity back to a {@code AgentId} (e.g. the debug selection glow).
     * Empty until the server assigns one. This carries only the opaque handle — identity
     * <em>content</em> (name, …) stays server-side.
     */
    private static final EntityDataAccessor<String> DATA_PERSON_ID =
            SynchedEntityData.defineId(Person.class, EntityDataSerializers.STRING);

    private static final String TAG_PERSON_ID = "AgentId";

    /** NBT key under which this entity persists its carried inventory (see {@link Inventories#CODEC}). */
    private static final String TAG_INVENTORY = "Inventory";

    /**
     * NBT keys under which this entity persists its food state — vanilla {@code FoodData}'s exact
     * four tags (names, types and load defaults verified against the 26.1.2 bytecode). Hunger is
     * physical state of <em>this</em> body and dies with it; durable memories are instead
     * {@link AgentId}-keyed.
     */
    /** The two switches a command set on this body — see {@code BrainDriver}. */
    private static final String TAG_BRAIN_AUTO = "BrainAuto";
    private static final String TAG_BRAIN_WANDER = "BrainWander";
    /** Aspect modifiers with no other source of truth — see {@link #modifiers()}. */
    private static final String TAG_MODIFIERS = "Modifiers";
    /** The walk in progress — route, waypoint and counters. See {@link #pendingWalk}. */
    private static final String TAG_NAV_WALK = "NavWalk";
    /** This body's stream of chance, so the roam it was going to pick is the one it picks. */
    private static final String TAG_BRAIN_RANDOM = "BrainRandom";
    /** Drives sitting out a fail-cooldown, by name, with the ticks they have left. */
    private static final String TAG_BRAIN_COOLDOWNS = "BrainCooldowns";
    /** The plan in progress and the grant that owns it — one tag, never two. */
    private static final String TAG_BRAIN_PLAN = "BrainPlan";
    /** What this body remembers of other bodies — tracks, linger and herds. */
    private static final String TAG_BEINGS = "Beings";
    /** This body's own account of itself — the journal ring `/anima log` reads. */
    private static final String TAG_JOURNAL = "Journal";
    /** The personal board's projects — all of layer 3 that lives on a body. */
    private static final String TAG_BOARD = "Board";
    /** A swing and a climb in flight — paired with the task flags that say one is under way. */
    private static final String TAG_SWING = "Swing";
    private static final String TAG_STEP = "Step";
    private static final String TAG_SETBACKS = "Setbacks";
    /** Ground this body has already surveyed, so it does not walk it all again. */
    private static final String TAG_SURVEY = "Survey";
    /** The anchor hunger is measured against, so a reload is not one free step. */
    private static final String TAG_LAST_X = "LastX";
    private static final String TAG_LAST_Z = "LastZ";

    private static final String TAG_FOOD_LEVEL = "foodLevel";
    private static final String TAG_FOOD_TICK_TIMER = "foodTickTimer";
    private static final String TAG_FOOD_SATURATION = "foodSaturationLevel";
    private static final String TAG_FOOD_EXHAUSTION = "foodExhaustionLevel";
    /**
     * How much company they had had when the world last saved. Persisted like the food bar:
     * loneliness that reset to comfortable on every restart would tell them a reboot happened.
     */
    private static final String TAG_COMPANY = "company";

    /** Whether this load has projected the directory identity onto the synced fields yet. */
    private boolean identityProjected;

    /**
     * The last encoded appearance seen on {@link #DATA_APPEARANCE}, and what it decoded and
     * composed to. Memo, not state: {@link #appearance()} is read on the render thread for every
     * Person on screen every frame, so decoding per call would allocate per Person per frame.
     *
     * <p>Invalidated by comparing against the raw synced string — {@code SynchedEntityData} offers
     * no change hook. That also makes a hot swap adding these fields safe: they arrive null, so the
     * first read rebuilds them.
     */
    private @Nullable String appearanceRaw;
    private @Nullable Appearance appearance;
    private @Nullable Recipe appearanceRecipe;

    /**
     * Where this body was walking when the world was last saved, handed back to the navigator on
     * the first tick.
     *
     * <p>The path, the grid and the follower's index are working state the world can rebuild from
     * the goal; the goal cannot be rebuilt, being an order somebody gave this body — which is why
     * the autonomy switch is saved beside it. Saving the switch alone was a real bug: a manual
     * {@code brain goto} turns autonomy off, so a reload restored a body in manual mode with
     * nothing to run, and it stood there for good.
     */
    private dev.luizloyola.anima.mod.nav.Navigator.@Nullable Walk pendingWalk;

    /** The saved plan and grant, and the saved board — restored together on the first tick, board
     *  first: the plan belongs to an errand the board has to hand back before the arbiter can be
     *  pointed at it. */
    private dev.luizloyola.anima.mod.brain.BrainDriver.@Nullable BrainSnapshot pendingBrain;
    private java.util.@Nullable List<dev.luizloyola.autarkia.core.board.KeepStocked.State> pendingBoard;

    /** This body's saved journal lines, waiting for a server to file them with. */
    private java.util.@Nullable List<dev.luizloyola.anima.core.log.Entry> pendingJournal;

    /**
     * The directory name, cached on the SERVER when the identity projects ({@link #applyIdentity}).
     * Never synced — a client learns names only through its contact book. Null on the client, and
     * on the server only until the first tick resolves the identity.
     */
    private @Nullable String identityName;

    /**
     * This entity's link to its identity in the world-scoped {@link PersonDirectory} — only a
     * reference, so the identity itself outlives the entity. {@code null} until first assigned.
     */
    private @Nullable AgentId personId;

    /**
     * Drives this person toward a target position (see {@link #serverAiStep()} /
     * {@link #navigateTo}) — the seam the pathfinder feeds. Transient: movement is
     * server-authoritative and not persisted across reloads.
     */
    private final Navigator navigator = new Navigator(this);

    /**
     * What this settler does about being in water — buoyancy, wading, and getting out again.
     * {@link Swimmer} is the single owner of every vertical press made while wet; this used to be
     * spread across this class and the {@link Navigator}, where each fix broke the next.
     */
    private final Swimmer swimmer = new Swimmer(this);

    /**
     * Where this settler is looking — and, when nothing needs its eyes, what it looks at of its own
     * accord. {@link Gaze} owns the head now; everything else asks. It had three writers and no
     * owner before, so a settler that stopped walking stood staring at its last waypoint.
     */
    private final Gaze gaze = new Gaze(this);

    /**
     * This person's own board — where wants stated about this body live, and nobody else can reach.
     * A project posted here is theirs for life (compose, don't merge — see {@link ComposedBoards}).
     *
     * <p><b>It posts no WORK</b>, and has not since 2026-08-15 (decision: Luiz): the standing
     * "keep 16 logs" quota seeded here was a disposable placeholder, and work comes from real
     * projects now. The {@link KeepStocked} machinery stays, and old saves still load — with
     * nothing posted, the restored rows have nothing to attach to. What it does carry, since
     * 2026-08-20, is {@link StandingWants}, which posts nothing either: it answers what this body
     * KEEPS, so the stow machinery can tell a settler's own axe from cargo.
     *
     * <p>Built inline, and never from {@code getId()}: a field initialiser runs before the level
     * assigns an entity id, which from 26.2 makes {@code getId()} throw on the client and drops the
     * player on the spawn packet. The seed this once took has been unused since the quota went.
     */
    private final PersonalBoard personalBoard = seededBoard();

    /** The board above: what this body keeps, and the standing want to put the rest away. */
    private static PersonalBoard seededBoard() {
        PersonalBoard board = new PersonalBoard();
        board.post(StandingWants.settlerDefaults());
        // Offset 0 rather than a per-body stagger: PersonalBoard.tick already runs on this
        // Person's own brain beat, so a settlement's cadences are spread by their bodies rather
        // than by a seed. The parameter stays for a species that wants to spread them further.
        board.post(new StowSurplus(0));
        return board;
    }

    /**
     * This person's brain host ({@link BrainDriver}) — a machine beside the {@link #navigator}, on
     * the other side of the machine/body split from {@link #inventory}/{@link #metabolism}: it runs
     * the task executor and only ever <em>reads</em> the body.
     */
    private final BrainDriver brain = new BrainDriver(this, new ComposedBoards(
            personalBoard.viewFor(this::getAgentId), this::partyWork));

    /**
     * The party board view handed to the brain, and the party it was built for — re-checked every
     * ask, rebuilt only when membership moves, since a view cached for life would quietly keep
     * serving a board they no longer belong to.
     */
    private @Nullable PartyId boardParty;
    private @Nullable WorkSource partyWork;

    /** This person's own board — what the board command reads and, later, posts to. */
    public PersonalBoard board() {
        return this.personalBoard;
    }

    /**
     * This person's face on their party's board, resolved on ask. Nothing before identity exists
     * (a body that does not yet know who it is cannot be a member of anything) and nothing on the
     * client, where there are no boards at all.
     */
    private WorkSource partyWork() {
        AgentId id = getAgentId();
        if (id == null || !(level() instanceof ServerLevel server)) {
            return WorkSource.NONE;
        }
        PartyId party = PartyData.get(server.getServer()).partyOf(id);
        if (!party.equals(this.boardParty) || this.partyWork == null) {
            this.boardParty = party;
            this.partyWork = PartyBoards.of(server.getServer(), party).viewFor(this::getAgentId);
        }
        return this.partyWork;
    }

    /**
     * This person's journal view ({@link AgentJournal}) — the one handle the brain, the
     * {@link #navigator} and the body all record through, resolved lazily and cached. Transient:
     * the log lives in the server-scoped {@link Journals} service.
     */
    private @Nullable AgentJournal journal;

    /**
     * This person's passive POI perception ({@link PoiSensor}, "notice as you go"): it records what
     * they move past into their {@link AgentId}-keyed knowledge — the memory the brain reads
     * instead of ever scanning the world, and it outlives the entity.
     */
    private final PoiSensor poiSensor = new PoiSensor(this);

    /**
     * This person's being sense ({@link dev.luizloyola.anima.mod.brain.BeingSense}) — eyes (cone +
     * line of sight), attention and object permanence over every living body around them. The brain
     * reads its output through {@code Percepts.beings()} and the person-filtered {@code peers()}.
     */
    private final dev.luizloyola.anima.mod.brain.BeingSense beingSense =
            new dev.luizloyola.anima.mod.brain.BeingSense(this);

    /**
     * The EAR half of the being sense — a game-event listener on the sculk vibration bus (see
     * {@link dev.luizloyola.anima.mod.brain.BeingEar}), registered with the level through
     * {@link #updateDynamicGameEventListener} the way the warden's is.
     */
    private final net.minecraft.world.level.gameevent.DynamicGameEventListener<dev.luizloyola.anima.mod.brain.BeingEar> ear =
            new net.minecraft.world.level.gameevent.DynamicGameEventListener<>(
                    new dev.luizloyola.anima.mod.brain.BeingEar(this));

    /**
     * This person's working arm ({@link AgentBlockBreaker}) — body machinery ticked here so the
     * crack animation, drops and exhaustion advance with the body, and exposed to the brain as an
     * actuator port by the {@link #brain} driver.
     */
    private final AgentBlockBreaker blockBreaker = new AgentBlockBreaker(this);
    private final AgentRiser riser = new AgentRiser(this);
    private final AgentLeaner leaner = new AgentLeaner(this);
    /**
     * Where these legs have lately been beaten. A body organ like the others and persisted like
     * them: a settler re-walking the doorway it was wedged in could tell a reboot had happened.
     */
    private final Setbacks setbacks = new Setbacks();

    /**
     * This person's food physiology ({@link Metabolism}) — body state beside the
     * {@link #inventory}, not a brain organ: the entity owns and ticks it, mirroring how vanilla's
     * {@code FoodData} belongs to the player and not to any AI, and the brain only ever
     * <em>reads</em> it. Persisted in this entity's NBT (see {@link #TAG_FOOD_LEVEL}), ticked by
     * {@link #tickNeeds()}.
     */
    private final Metabolism metabolism = new Metabolism();

    /**
     * How much company this settler has had lately — the one gauge below that is its own number
     * rather than a view. Fed each tick from the being sense and persisted like the food bar.
     *
     * <p>Takes the profile as a SUPPLIER: {@link #profile()} builds lazily and this is a field
     * initialiser, so asking for it here would ask this body what species it is while its fields
     * are still being assigned.
     */
    private final Company company = new Company(this::profile);

    /**
     * Everything this settler feels, in one roster: hunger (a view over the {@link #metabolism},
     * never a second number), breath (a view over the air supply the game already keeps),
     * {@link #company}, and vigor — hit points read off the same organ, less what is dragging this
     * body down and plus what is holding it up. One tick site, one readout, and where a registered
     * need would appear.
     */
    private final Needs needs = new Needs()
            .add(new FoodNeed(this.metabolism, this::profile))
            .add(new BreathNeed(this::getAirSupply, this::getMaxAirSupply, this::profile))
            .add(this.company)
            .add(new Vigor(this.metabolism, BodyEffects.of(this), this::profile));

    /**
     * Per-agent aspect modifiers — see {@link #profile()}. Empty until something shifts one.
     *
     * <p>Lazy rather than a field initialiser: the organs above are themselves initialisers, and
     * they ask this body what it is <em>while</em> the fields are still being assigned.
     */
    private AgentModifiers modifiers;
    /**
     * One profile object for this body's whole life. The resolved read behind it caches its fold
     * and re-does it when the config or the modifiers move, so a fresh wrapper per call would throw
     * that cache away — and the navigator alone asks every tick. Lazy like {@link #modifiers}.
     */
    private AgentProfile profile;

    /**
     * Previous-tick position for the sprint-exhaustion odometer in {@link #tickNeeds()}, kept apart
     * from the {@link Navigator}'s own stall-detection pair. Seeded on the first tick ({@code NaN})
     * so a freshly loaded entity never bills the distance from the origin.
     */
    private double lastX = Double.NaN;
    private double lastZ;

    /**
     * This person's carried inventory — 9 hotbar + 27 main + 4 armor + 1 offhand, exactly a player's
     * 41 slots, and the <em>source of truth</em>: the equipment slots are mirrored onto this entity
     * each server tick ({@link #syncEquipmentMirror()}), and the whole thing persists in NBT and
     * drops on death. Item components ride along losslessly, as an opaque {@code core} payload.
     */
    private final Inventory inventory = new Inventory();

    /**
     * The last value {@link #syncEquipmentMirror() the equipment mirror} synced for each slot, in
     * core form — the reference for deciding which side moved. Transient; rebuilt from the inventory
     * on the first tick after load.
     */
    private final java.util.EnumMap<EquipmentSlot, dev.luizloyola.anima.core.inv.ItemStack> mirroredEquipment =
            new java.util.EnumMap<>(EquipmentSlot.class);

    /**
     * A copy of the vanilla stack last pushed to each equipment slot, so a cheap {@code
     * ItemStack.matches} tells us whether vanilla mutated the slot (totem consumed, armor durability
     * spent) without re-encoding component SNBT every tick. Transient.
     */
    private final java.util.EnumMap<EquipmentSlot, ItemStack> mirroredVanilla =
            new java.util.EnumMap<>(EquipmentSlot.class);

    /**
     * A player's forward movement input is damped to {@code 0.98} before it reaches {@code travel}
     * each tick, so our driver applies the same factor: a raw {@code 1.0} walks ~2% faster than a
     * player (measured 1.020×; {@code 1/0.98}).
     */
    private static final float PLAYER_INPUT_DAMPING = 0.98F;

    /**
     * Swappable construction hook so the client can substitute {@code ClientPerson} (which
     * resolves a client-side skin) for the plain server entity. Mirrors vanilla's
     * {@code Mannequin}/{@code ClientMannequin} split.
     */
    public static EntityType.EntityFactory<Person> factory = Person::new;

    public Person(EntityType<? extends Person> type, Level level) {
        super(type, level);
    }

    /** Registry factory; delegates through {@link #factory} to allow the client-twin swap. */
    public static Person create(EntityType<Person> type, Level level) {
        return factory.create(type, level);
    }

    public static AttributeSupplier.Builder createAttributes() {
        // A Person drives itself through the same LivingEntity.travel physics a player uses, so
        // matching a player's walk means matching MOVEMENT_SPEED (0.1) — verified in-world side by
        // side, within ~2%. STEP_HEIGHT stays at the living default (0.6, player-equal): slabs and
        // stair-bottoms walkable, full blocks still need a jump. AgentAttributes adds the mining
        // and combat sets — see there for why an undeclared attribute is a silent hole, not a zero.
        AttributeSupplier.Builder builder = LivingEntity.createLivingAttributes()
                .add(Attributes.MOVEMENT_SPEED, 0.1);
        AgentAttributes.mining(builder);
        AgentAttributes.combat(builder);
        return builder;
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        super.defineSynchedData(builder);
        builder.define(DATA_APPEARANCE, Appearance.DEFAULT.encode());
        builder.define(DATA_PERSON_ID, "");
    }

    @Override
    public void tick() {
        // Identity before super.tick(), which runs serverAiStep: a plainly summoned Person has
        // none and would otherwise tick its AI once with a null personId — a latent trap for the
        // AgentId-keyed knowledge the brain grows. Doing it first restores the "identity before
        // AI" invariant /autarkia spawn guarantees by assigning before addFreshEntity. Lazy
        // because it needs the running server; Avatar is not a Mob, so there is no finalizeSpawn.
        if (this.level() instanceof ServerLevel serverLevel
                && (this.personId == null || !this.identityProjected)) {
            PersonDirectory directory = PersonDirectory.get(serverLevel.getServer());
            if (this.personId == null) {
                setAgentId(directory.createPerson().id());
            }
            // Mirror the public appearance onto the synced fields, once per load — the directory is
            // the source of truth.
            if (!this.identityProjected) {
                directory.find(this.personId).ifPresent(this::applyIdentity);
                this.identityProjected = true;
            }
        }
        // Outside the identity block on purpose: that one stops running once a Person is projected,
        // and a check that quietly stops running is worse than none. One boolean read a tick.
        if (!CHUNK_SAVE_CHECKED && this.level() instanceof ServerLevel) {
            verifyChunkSaved();
        }
        if ((this.pendingBrain != null || this.pendingBoard != null) && this.personId != null
                && this.level() instanceof ServerLevel boardLevel) {
            // Board first: it hands back the errand the plan belongs to, and the arbiter is
            // pointed at that exact item rather than at a flag with nothing behind it.
            var board = this.pendingBoard;
            var brainState = this.pendingBrain;
            this.pendingBoard = null;
            this.pendingBrain = null;
            dev.luizloyola.anima.core.brain.board.WorkItem held = null;
            if (board != null) {
                held = this.personalBoard
                        .restore(board, this.personId, boardLevel.getGameTime())
                        .orElse(null);
            }
            if (brainState != null) {
                this.brain.restore(brainState, held);
            }
        }
        if (this.pendingJournal != null && this.personId != null
                && this.level() instanceof ServerLevel journalLevel) {
            var lines = this.pendingJournal;
            this.pendingJournal = null;
            Journals.of(journalLevel.getServer()).restore(this.personId, lines);
        }
        if (this.pendingWalk != null && this.level() instanceof ServerLevel) {
            var walk = this.pendingWalk;
            this.pendingWalk = null; // one attempt; a broken route is the navigator's to report
            this.navigator.restore(walk);
            journal().record(Category.BODY, "resumed", "walking to "
                    + (walk.goal() == null ? "nowhere" : walk.goal().toShortString()));
        }
        super.tick();
        // After super.tick(), where vanilla puts the equivalent call for a player. The flag it
        // reads was set by baseTick's updateSwimming, which runs before the navigator, so it
        // answers with last tick's leg — one tick of lag, against a second call site that would
        // have to agree with the first. Server side only: the pose is synched.
        if (level() instanceof ServerLevel) {
            updatePose();
        }
    }

    /**
     * Checks once per JVM that a Person is still written into its chunk, because the fact that one
     * is rests on an accident.
     *
     * <p>{@code Entity.shouldBeSaved()} defaults to true and {@code Player} overrides it to false;
     * {@link Avatar}, split OUT of {@code Player} at 1.21.9, kept the default (verified by
     * disassembling 26.1.2). If a release moves that override up onto {@code Avatar}, every Person
     * silently stops being written and the settlement is gone after the next reload, with nothing
     * pointing at the cause.
     *
     * <p>Deferred to the first tick because the answer is a property of an instance, and asking by
     * name through reflection would be a string mappings can invalidate. Gated on the states where
     * vanilla legitimately answers false — passenger, ridden vehicle, already removed.
     */
    private void verifyChunkSaved() {
        if (isPassenger() || isVehicle() || isRemoved()) {
            return; 
        }
        CHUNK_SAVE_CHECKED = true;
        if (!shouldBeSaved()) {
            AutarkiaMod.LOGGER.error("PERSONS ARE NOT BEING SAVED. shouldBeSaved() is false for a "
                    + "plain standing Person, which means Minecraft now excludes this entity from "
                    + "chunk data — most likely because Avatar picked up Player's override. Every "
                    + "settler in this world will be gone after the next restart, and nothing else "
                    + "will report it. Do not keep playing a world you care about.");
        }
    }

    /** One answer per JVM — see {@link #verifyChunkSaved}. */
    private static boolean CHUNK_SAVE_CHECKED;

    /**
     * Both-sides tick step. {@code updateSwingTime()} is the clock behind the visible arm-swing
     * animation, and in vanilla it is driven only from {@code Player.aiStep} (verified against
     * the 26.1.2 bytecode — neither {@code LivingEntity} nor {@code Mob} advances it). An
     * {@link Avatar} is not a Player, so without this the working arm's swing broadcast sets
     * {@code swinging} on every client and then animates nothing — frozen at frame zero.
     */
    @Override
    public void aiStep() {
        super.aiStep();
        this.updateSwingTime();
    }

    /**
     * The shoulders, on both sides: square to {@code yRot}, which is the invariant {@link Gaze}
     * already holds server-side — every writer of the pair sets them together.
     *
     * <p>It has to be restored here because {@code yBodyRot} is in no packet. A client rebuilds it
     * from vanilla's guess — ease toward the walk heading, then clamp within 50° of {@code yRot}
     * — and a body turning on the spot moves nowhere, so the ease has no target and only the clamp
     * acts. That clamp does not converge: it parks the shoulders at exactly {@code yRot - 50°} and
     * leaves them there until the body walks or swings. The head, which IS synced and which the
     * gaze organ allows 60° of twist of its own, then rendered up to 110° round — a neck no body
     * has, on a person whose shoulders still faced the way they came from.
     *
     * <p>Snapping is safe: {@code yRot} is already interpolated client-side before this runs, and
     * {@code yBodyRotO} was captured earlier in the tick, so the render still eases between them.
     *
     * @param target vanilla's guess at where the shoulders belong — the thing being replaced
     */
    @Override
    protected void tickHeadTurn(float target) {
        this.yBodyRot = getYRot();
    }

    @Override
    protected void serverAiStep() {
        super.serverAiStep();
        // Gather any dropped items we're standing over — before the mirror runs, so a caught item
        // that lands in the selected hotbar slot is held (and rendered) this same tick.
        pickUpNearbyItems((ServerLevel) level());
        syncEquipmentMirror();
        // Metabolism runs every server tick, whoever owns the movement input below.
        tickNeeds();
        // Perception before decision: the sensor notices what they moved past and writes it into
        // their knowledge; the brain (below) reads memory, never the world.
        this.poiSensor.tick();
        this.beingSense.tick();
        // The brain decides first, then the Navigator (below) executes locomotion the same tick.
        this.brain.tick();
        // The working arm advances after the brain, so a break begun this tick gains its first
        // progress this tick — same-tick actuation, like the navigator below.
        this.blockBreaker.tick();
        // The navigator owns the forward input.
        this.navigator.tick();
        // The riser after the navigator: an idle Navigator's tick stops all movement inputs
        // ("never coast on stale input"), which would wipe the rise's centring shuffle and its
        // held jump every tick.
        this.riser.tick();
        // The lean after the riser, for the same reason. The two are never asked for at once.
        this.leaner.tick();
        // The gaze after everything that might want the head: this tick's claims are its input,
        // and what is left over is the body's own to look at. Before the swimmer, which owns a
        // wet body's pitch.
        this.gaze.tick();
        // The swimmer last: it reads the follower's water intent for this tick, and it owns every
        // vertical press while wet, so it has to be the last word on the matter.
        this.swimmer.tick();
    }

    /**
     * Publishes the {@link Swimmer}'s verdict onto the flag vanilla keeps for every entity.
     *
     * <p>{@code Entity.updateSwimming} asks "is it SPRINTING and under water", which a Person can
     * essentially never answer yes: its sprint is gated on {@link #driveSprint} and the metabolism,
     * so a tired settler could not swim, and the swimmer keeps the head at the surface, so the
     * eyes-under half is true only in passing.
     *
     * <p>It runs from {@code baseTick}, <em>before</em> {@code serverAiStep} ticks the swimmer, so
     * the flag published here is the previous tick's — one tick of lag against a second call site
     * that would have to agree with this one forever.
     *
     * <p>Server only: the flag is synched entity data, and a client that ran this would fight the
     * server and lose, its own organs never ticking.
     */
    @Override
    public void updateSwimming() {
        if (level() instanceof ServerLevel) {
            setSwimming(this.swimmer.isSwimming());
        }
    }

    /**
     * Puts the body into the shape the swim flag, or the lean, says it is in — a Person's version of
     * {@code Player.updatePlayerPose}, which does not apply to an {@link Avatar}. Everything else
     * already works unmodified: {@code Avatar.POSES} carries the 0.6×0.6 swimming box,
     * {@code LivingEntity.tick} ramps {@code swimAmount} off it, and {@code AvatarRenderer} reads
     * both.
     *
     * <p>A pose change resizes the hitbox in place, so the fit check keeps a body that swam under a
     * ledge from standing up into it — vanilla guards the same way. Refusing to stand leaves them
     * swimming, which out of water is vanilla's crawl.
     */
    private void updatePose() {
        // Read from the organ, not the synched flag: the flag is published in baseTick, before the
        // swimmer decides, so following it put the pose two ticks behind — a settler flapping on
        // the shore after its feet were down. The flag stays a tick behind for vanilla's own uses.
        Pose desired = this.swimmer.isSwimming() ? Pose.SWIMMING
                : this.leaner.crouching() ? Pose.CROUCHING : Pose.STANDING;
        if (getPose() == desired || !fitsAs(desired)) {
            return;
        }
        setPose(desired);
    }

    /** Whether the body's box in {@code pose} would be clear of the world where it stands now. */
    private boolean fitsAs(Pose pose) {
        return level().noCollision(
                this, getDimensions(pose).makeBoundingBox(position()).deflate(1.0E-7));
    }

    /** This person's movement/navigation state machine. See {@link Navigator}. */
    public Navigator navigator() {
        return this.navigator;
    }

    /** This person's brain host — the machine that runs tasks. See {@link BrainDriver}. */
    public BrainDriver brain() {
        return this.brain;
    }

    /** This person's passive POI perception — fills their knowledge. See {@link PoiSensor}. */
    public PoiSensor poiSensor() {
        return this.poiSensor;
    }

    /** This person's being sense — eyes, ears, attention. See {@link dev.luizloyola.anima.mod.brain.BeingSense}. */
    public dev.luizloyola.anima.mod.brain.BeingSense beingSense() {
        return this.beingSense;
    }

    /** Registers the ear with the level's vibration bus — the warden's own registration path. */
    @Override
    public void updateDynamicGameEventListener(
            java.util.function.BiConsumer<net.minecraft.world.level.gameevent.DynamicGameEventListener<?>, net.minecraft.server.level.ServerLevel> consumer) {
        if (this.level() instanceof net.minecraft.server.level.ServerLevel serverLevel) {
            consumer.accept(this.ear, serverLevel);
        }
    }

    /** This person's working arm — the break machinery the brain drives as a port. See {@link AgentBlockBreaker}. */
    public AgentBlockBreaker blockBreaker() {
        return this.blockBreaker;
    }

    /** This person's rise-one machinery — jump-and-place-underfoot, brain-driven as a port. */
    @Override
    public AgentRiser riser() {
        return this.riser;
    }

    public AgentLeaner leaner() {
        return this.leaner;
    }

    /** Where these legs have lately been beaten — see {@link Setbacks}. */
    @Override
    public Setbacks setbacks() {
        return this.setbacks;
    }

    /** This person's water organ — buoyancy, wading, climbing out. See {@link Swimmer}. */
    @Override
    public Swimmer swimmer() {
        return this.swimmer;
    }

    /** Where this person is looking, and what it looks at unprompted. See {@link Gaze}. */
    @Override
    public Gaze gaze() {
        return this.gaze;
    }

    /** This person's food physiology — body state the brain reads, never owns. See {@link #metabolism}. */
    @Override
    public Metabolism metabolism() {
        return this.metabolism;
    }

    /** Every gauge this person feels. See {@link #needs}. */
    @Override
    public Needs needs() {
        return this.needs;
    }

    /** This person's company gauge, typed — for the load path and the dev command that stages a mood. */
    public Company company() {
        return this.company;
    }

    /**
     * This person's journal — the single {@code AgentId}-bound view onto the server's
     * {@link Journals} service, resolved once and cached (the id, once assigned, never changes).
     * Server-side only, and past identity resolution, so it mints an id only if this body is hit
     * before its first tick — which keeps the view always valid rather than ever {@code null}.
     */
    public AgentJournal journal() {
        if (this.journal == null) {
            ServerLevel level = (ServerLevel) level();
            if (this.personId == null) {
                setAgentId(PersonDirectory.get(level.getServer()).createPerson().id());
            }
            this.journal = Journals.of(level.getServer()).forPerson(this.personId);
        }
        return this.journal;
    }

    /**
     * Formats a health/damage number for a log line: a whole value prints without a trailing
     * {@code ".0"} ({@code "4"}, {@code "20"}), a half-heart keeps its fraction ({@code "4.5"}).
     */
    private static String num(float value) {
        return value == Math.rint(value) ? Integer.toString((int) value) : Float.toString(value);
    }

    /**
     * One tick of metabolism, from {@link #serverAiStep()} — the body's counterpart of vanilla
     * {@code FoodData.tick(ServerPlayer)}:
     *
     * <ol>
     *   <li><b>Movement exhaustion</b> — vanilla's 0.1/m sprinting and 0.01/m swimming, walking
     *       free, measured against last tick's position.</li>
     *   <li><b>Regen/starvation inputs</b> — the {@code naturalRegeneration} gamerule and this
     *       body's hit points, which are also how the metabolism comes to have health to offer
     *       ({@code Metabolism.health()}); it works hurt-ness out from them, since
     *       {@code isHurt()} is Player-only on 26.1.2 and was always that comparison.</li>
     *   <li><b>Effects</b> — core decides ({@link Metabolism.TickResult}), the body applies: a
     *       half-heart on regen, a starvation hit on vanilla's 80-tick cadence, not
     *       difficulty-clamped because starvation has to be able to kill.</li>
     *   <li><b>Every other gauge</b> — the {@link #needs} roster last, on the same beat; food is a
     *       view over the organ above and does nothing there.</li>
     * </ol>
     */
    private void tickNeeds() {
        boolean firstSample = Double.isNaN(this.lastX);
        if (!firstSample) {
            double dx = getX() - this.lastX;
            double dz = getZ() - this.lastZ;
            float meters = (float) Math.sqrt(dx * dx + dz * dz);
            if (meters > 0.0F) {
                if (isInWater()) {
                    this.metabolism.exhaust(Metabolism.EXHAUSTION_SWIM_PER_METER * meters);
                } else if (isSprinting() && onGround()) {
                    this.metabolism.exhaust(Metabolism.EXHAUSTION_SPRINT_PER_METER * meters);
                }
            }
        }
        this.lastX = getX();
        this.lastZ = getZ();
        // Hunger/Saturation status effects, mirrored: vanilla's HungerMobEffect and
        // SaturationMobEffect apply only to `instanceof Player` (26.1.2 bytecode), so on a Person
        // they no-op. Same numbers and cadence: Hunger banks 0.005 exhaustion per level, Saturation
        // feeds (level+1) food at the ×1.0 modifier.
        MobEffectInstance hungerEffect = getEffect(MobEffects.HUNGER);
        if (hungerEffect != null) {
            this.metabolism.exhaust(0.005F * (hungerEffect.getAmplifier() + 1));
        }
        MobEffectInstance saturationEffect = getEffect(MobEffects.SATURATION);
        if (saturationEffect != null) {
            int nutrition = saturationEffect.getAmplifier() + 1;
            this.metabolism.eat(nutrition, Metabolism.saturationByModifier(nutrition, 1.0F));
        }
        boolean naturalRegen =
                ((ServerLevel) level()).getGameRules().get(GameRules.NATURAL_HEALTH_REGENERATION);
        Metabolism.TickResult result =
                this.metabolism.tick(naturalRegen, getHealth(), getMaxHealth());
        if (result.heal() > 0.0F) {
            heal(result.heal());
        }
        if (result.starve()) {
            // hurtServer, not the deprecated side-dispatching hurt(): only ever ticked from
            // serverAiStep, so the level is always the server one (same cast as the Navigator).
            hurtServer((ServerLevel) level(), damageSources().starve(), 1.0F);
        }
        this.company.observe(knownPeopleNearby());
        this.needs.tick();
        recordBreathChange();
    }

    /**
     * Writes a line when this settler's breath level changes — the whole of what makes drowning
     * legible, since nothing yet acts on it.
     *
     * <p>Every change, not just the alarming ones, which is affordable because air only moves while
     * the EYES are under: swimming across a lake with the head up costs nothing, and a crossing
     * that does dip produces a line or two.
     *
     * <p>The first observation after a load only remembers, never writes — otherwise every restart
     * would announce a level the settler has been at all along.
     */
    private void recordBreathChange() {
        NeedLevel level = this.needs.gauge(NeedKind.BREATH).map(Gauge::level).orElse(null);
        if (level == null || level.key().equals(this.lastBreathLevel)) {
            return;
        }
        String had = this.lastBreathLevel;
        this.lastBreathLevel = level.key();
        if (had != null) {
            journal().record(Category.BODY, "breath", had + " -> " + level.key());
        }
    }

    /** What {@link #recordBreathChange()} last saw; null until the first observation after a load. */
    private @Nullable String lastBreathLevel;

    /**
     * How long this particular settler can hold its breath — per agent, read live, and the same
     * number its breath need calls a full lungful. Vanilla hands every living thing a flat 300.
     *
     * <p>The need's own {@code easy} boundary rather than a capacity aspect beside it:
     * a body that holds 600 ticks while its need thinks comfort arrives at 300 would spend half of
     * every lungful reporting a pressure it has no business feeling. One declaration answers both,
     * and a modifier that deepens one deepens the other.
     *
     * <p>Read through on every call, so a config reload retunes a settler already under water.
     */
    @Override
    public int getMaxAirSupply() {
        return (int) FULL_BREATH.value(profile());
    }

    /**
     * The level whose value is a full lungful. Resolved once by name; an absent one is a rename
     * that has to fail loudly here rather than quietly become a capacity of zero.
     */
    private static final NeedLevel FULL_BREATH = NeedKind.BREATH.level("easy").orElseThrow(
            () -> new IllegalStateException("the breath need has no \"easy\" level to size a "
                    + "lungful from — see Person.getMaxAirSupply"));

    /**
     * How many people this settler can currently perceive and has already met — what feeds the
     * company gauge. <b>Minded</b> is the being sense's word for "a person" and covers live players
     * exactly like settlers; <b>{@code INDIVIDUAL}</b> is the evidence gate, since a figure made out
     * at a distance cannot be somebody you know; <b>in the contact book</b> keeps a stranger beside
     * you from satisfying the drive to go and introduce yourself.
     *
     * <p>Reads the sense's retained tracks rather than forcing a scan — what it last decided on its
     * own near/far cadence.
     */
    private int knownPeopleNearby() {
        AgentId me = agentId();
        if (me == null) {
            return 0;
        }
        ContactData contacts = ContactData.get(((ServerLevel) level()).getServer());
        int count = 0;
        for (Being being : this.beingSense.beings()) {
            if (being.kind().minded() && being.identified() == Being.Identified.INDIVIDUAL
                    && contacts.knows(me, being.id().asPerson())) {
                count++;
            }
        }
        return count;
    }

    /**
     * Charges vanilla's jump exhaustion (0.2 sprinting, 0.05 plain) where vanilla hooks it:
     * {@code ServerPlayer.jumpFromGround} wraps the physics with {@code causeFoodExhaustion}
     * (bytecode-verified on 26.1.2). The physics call runs on both sides, so the guard keeps body
     * state server-authoritative.
     */
    @Override
    public void jumpFromGround() {
        super.jumpFromGround();
        if (!level().isClientSide()) {
            this.metabolism.exhaust(isSprinting() ? Metabolism.EXHAUSTION_SPRINT_JUMP : Metabolism.EXHAUSTION_JUMP);
        }
    }

    /** This person's carried inventory — the source of truth the brain (and commands) read/write. */
    public Inventory inventory() {
        return this.inventory;
    }

    /**
     * Keeps the inventory's equipment slots — main hand (the selected hotbar slot), offhand and the
     * four armor pieces — in sync with this entity's real equipment, so vanilla renders them and
     * applies armor mechanics. <b>Two-way:</b> a core change is pushed onto the entity, and a change
     * vanilla made (a totem consumed, armor durability spent) is pulled back into the inventory.
     */
    private void syncEquipmentMirror() {
        reconcile(EquipmentSlot.MAINHAND, this.inventory.selectedSlot());
        reconcile(EquipmentSlot.OFFHAND, Inventory.OFFHAND_SLOT);
        reconcile(EquipmentSlot.HEAD, armorSlotIndex(ArmorType.HEAD));
        reconcile(EquipmentSlot.CHEST, armorSlotIndex(ArmorType.CHEST));
        reconcile(EquipmentSlot.LEGS, armorSlotIndex(ArmorType.LEGS));
        reconcile(EquipmentSlot.FEET, armorSlotIndex(ArmorType.FEET));
    }

    private static int armorSlotIndex(ArmorType type) {
        return Inventory.ARMOR_START + type.ordinal();
    }

    /**
     * Syncs one core inventory slot with its vanilla equipment slot. If core moved since the last
     * sync we push it onto the entity; otherwise, if vanilla mutated the entity slot (cheap
     * {@code ItemStack.matches} against what we last pushed), we pull that back into the inventory.
     */
    private void reconcile(EquipmentSlot slot, int coreSlot) {
        dev.luizloyola.anima.core.inv.ItemStack coreStack = this.inventory.get(coreSlot);
        if (!coreStack.equals(this.mirroredEquipment.get(slot))) {
            ItemStack pushed = ItemStacks.toVanilla(coreStack, registryAccess());
            setItemSlot(slot, pushed);
            this.mirroredEquipment.put(slot, coreStack);
            this.mirroredVanilla.put(slot, pushed.copy());
            return;
        }
        ItemStack entityStack = getItemBySlot(slot);
        ItemStack lastPushed = this.mirroredVanilla.get(slot);
        if (lastPushed != null && ItemStack.matches(entityStack, lastPushed)) {
            return; // vanilla didn't touch it
        }
        dev.luizloyola.anima.core.inv.ItemStack entityAsCore = ItemStacks.toCore(entityStack, registryAccess());
        this.inventory.set(coreSlot, entityAsCore);
        this.mirroredEquipment.put(slot, entityAsCore);
        this.mirroredVanilla.put(slot, entityStack.copy());
    }

    /**
     * Send this person to the cell containing {@code target} (world coordinates): the
     * {@link Navigator} computes a route (off the main thread) and walks it.
     */
    public void navigateTo(Vec3 target) {
        this.navigator.pathTo(net.minecraft.core.BlockPos.containing(target));
    }

    /**
     * The same order at a chosen pace. The gait is advisory — the follower still slows for careful
     * ground and still takes a leap's run-up at full speed.
     */
    public void navigateTo(Vec3 target, Gait gait) {
        this.navigator.pathTo(net.minecraft.core.BlockPos.containing(target), gait);
    }

    /**
     * Opens this Person's inventory as a container screen (all 41 slots) for {@code player}, backed
     * by a live {@link PersonContainer} over the core inventory. Server-authoritative.
     *
     * <p>Anima's {@code AgentBody} hook, driven by {@code /anima inv see}. It used to be a Fabric
     * {@code UseEntityCallback} registered here; social rung 7 gave that click to the hail and the
     * library kept the command, which is a better home for a dev tool anyway — the whole
     * {@code /anima} tree is op-gated, and an empty-handed right-click is not.
     */
    @Override
    public boolean showInventory(ServerPlayer player) {
        player.openMenu(new SimpleMenuProvider(
                (syncId, playerInv, opener) ->
                        new PersonInventoryMenu(syncId, playerInv, new PersonContainer(this), getId()),
                getName()));
        return true;
    }

    /**
     * Wears down worn armor on taking damage: vanilla's {@code hurtArmor} no-ops for a generic
     * {@code LivingEntity}, so a Person's gear would never wear out. {@link #doHurtEquipment}
     * applies vanilla's own durability rules, and the two-way {@link #syncEquipmentMirror() mirror}
     * carries the wear back into the inventory. Also charges the damage type's food exhaustion, as
     * vanilla's player does (verified on 26.1.2).
     */
    @Override
    protected void actuallyHurt(ServerLevel level, DamageSource source, float amount) {
        float healthBefore = getHealth();
        super.actuallyHurt(level, source, amount);
        doHurtEquipment(source, amount,
                EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET);
        this.metabolism.exhaust(source.getFoodExhaustion());
        // BODY log: the health actually lost (armor/absorption may have trimmed the raw amount) and
        // the resulting health. actuallyHurt is past the invulnerability gate, so every call is a
        // real hit.
        journal().record(Category.BODY, "health", String.format(Locale.ROOT,
                "took %s damage (%s) now %s/%s",
                num(healthBefore - getHealth()), source.getMsgId(), num(getHealth()), num(getMaxHealth())));
    }

    /**
     * Movement control (the primitive the {@link Navigator} drives): face {@code heading} and apply
     * a single tick of forward walking input. Vanilla physics turns that into motion in
     * {@link #travel} — see {@link #serverAiStep()}. Must be called every tick to keep moving; the
     * input is consumed and reset each tick.
     */
    public void driveForward(float heading) {
        driveForward(heading, 1.0F);
    }

    /**
     * As {@link #driveForward(float)}, with the forward input scaled by {@code throttle} (0..1) —
     * the navigator's careful gait near edges. Like a player easing the stick, not a speed
     * attribute change: friction and physics stay vanilla.
     */
    public void driveForward(float heading, float throttle) {
        face(heading);
        // getAttributeValue includes any sprint modifier; for a plain (non-sprinting) walk this is
        // MOVEMENT_SPEED (0.1), and the 0.98 damping keeps it exact versus a walking player.
        setSpeed((float) getAttributeValue(Attributes.MOVEMENT_SPEED));
        this.zza = PLAYER_INPUT_DAMPING * throttle; // forward along the current yRot; no strafe
        // jumping is a HELD input on LivingEntity — aiStep only clears it for immobile entities
        // (26.1.2 bytecode). Left set, one press would auto-jump every landing, so inputs here are
        // per-tick: forward clears it, driveJump() re-presses it.
        setJumping(false);
    }

    /** Movement control: hold still this tick — no forward input, no jump, walk gait. */
    public void stopMoving() {
        this.zza = 0.0F;
        this.yya = 0.0F;
        setJumping(false);
        // A navigator that stops mid-sprint must not leave the ×1.3 modifier latched for its
        // next plain walk.
        if (isSprinting()) {
            setSprinting(false);
        }
    }

    /**
     * Movement control: choose this tick's gait. Sprinting adds vanilla's ×1.3 speed modifier
     * (read by {@link #driveForward} — call this first) and the sprint-jump forward boost. Guarded
     * on change, since {@code setSprinting} churns an attribute modifier, and gated on
     * {@link Metabolism#canSprint()} when enabling; disabling is always allowed.
     *
     * <p>A food&le;6 Person therefore cannot sprint, so 3-gap leap paths fail and the pathfinder
     * does not know yet (deferred: hunger-aware {@code MoveCapabilities}).
     */
    public void driveSprint(boolean sprint) {
        if (sprint && !this.metabolism.canSprint()) {
            sprint = false;
        }
        if (isSprinting() != sprint) {
            setSprinting(sprint);
        }
    }

    /**
     * Movement control: press jump this tick. Call <em>after</em> {@link #driveForward}, which clears
     * the held jump input. Effective because the navigator ticks inside {@link #serverAiStep()},
     * before aiStep's ground-jump check.
     */
    public void driveJump() {
        setJumping(true);
    }

    /**
     * Movement control: swim down. Vanilla's fluid travel reads {@code yya} as the vertical half of
     * the movement input (a player's sneak key drives the same field), and it is the only way
     * down. Per tick like {@code zza}: {@code travel} consumes it and {@link #stopMoving} clears
     * it, so a dive that ends leaves nothing latched pulling a settler to the next lake's bottom.
     */
    @Override
    public void driveDown(float throttle) {
        this.yya = -PLAYER_INPUT_DAMPING * throttle;
    }

    /**
     * Steer toward {@code heading} (degrees) — the walking half of facing, and the whole of what
     * the legs write. {@code yRot} is the steering wheel: vanilla's {@code travel} rotates the
     * movement input by it. {@code yBodyRot} goes with it so the shoulders are square to the walk
     * from the first tick.
     *
     * <p><b>The head is not here.</b> Snapping {@code yHeadRot} every walking tick is
     * why a body that stopped walking froze looking at its last waypoint. The legs now
     * <em>ask</em>, at {@link Gazer.Priority#NAV}, and {@link Gaze} decides.
     */
    private void face(float heading) {
        setYRot(heading);
        this.yBodyRot = heading;
        this.gaze.lookAlong(heading, Gazer.Priority.NAV, 1);
    }

    /**
     * Push a person's EXTERNAL identity onto the synced fields (server-side): skin, gender, model —
     * what anyone can see by looking. The NAME does not ride along: a custom name is
     * broadcast to every client tracking the entity, which is the leak the contact book closes. It
     * now reaches a client only through {@code ContactsPayload}, addressed to the player who earned
     * it, and {@code PersonRenderer} decides the nameplate from there. Server-side the name is
     * unchanged and omniscient — see {@link #getName()}.
     */
    private void applyIdentity(PersonIdentity identity) {
        this.identityName = identity.name();
        Appearance identityAppearance = identity.appearance();
        // Self-healing migration: Autarkia used to bundle its own skin PNGs, so a person created
        // before that points at a texture not in the jar — and a missing texture is not a crash,
        // it is a magenta-and-black person forever. Corrected once, on first load, rather than
        // resolved on every read with the directory disagreeing with the renderer; they are healed
        // into a composed look rolled from their own id.
        //
        // Scoped to our namespace: a pin naming anything else is somebody's deliberate choice, and
        // a dedicated server ships no client assets, so it can no more confirm that
        // `minecraft:entity/player/wide/steve` exists than deny it.
        if (identityAppearance.look() instanceof Look.Skin worn
                && worn.assetId().startsWith(AutarkiaMod.MOD_ID + ":")
                && !PersonAppearance.has(worn.assetId())
                && level() instanceof ServerLevel serverLevel) {
            identityAppearance = identityAppearance.withLook(
                    PersonDirectory.composedLookFor(identity.id(), identityAppearance.gender()));
            PersonDirectory.get(serverLevel.getServer())
                    .replace(identity.withAppearance(identityAppearance));
        }
        this.entityData.set(DATA_APPEARANCE, identityAppearance.encode());
    }

    /**
     * This person's name, SERVER-SIDE and omniscient — commands, journals and logs all read it
     * here, unchanged by the contact book.
     *
     * <p>Overriding this rather than the custom name keeps every server-side caller working, since
     * {@code Entity#getDisplayName} builds on it. On the CLIENT it falls through to the entity
     * type, and the renderer asks {@code AgentContactsClient} instead.
     */
    @Override
    public Component getName() {
        return this.identityName == null ? super.getName() : Component.literal(this.identityName);
    }

    /**
     * A Person always OFFERS a nameplate; whether a viewer can read it is decided on their own
     * client, from their contact book ({@code PersonRenderer#shouldShowName}). Vanilla would gate
     * this on the custom-name flag, which the name no longer uses.
     */
    @Override
    public boolean shouldShowName() {
        return true;
    }

    /**
     * Records the death the way vanilla records a player's, because vanilla will not: a Person is
     * not a {@code ServerPlayer}, so no death message is broadcast, and {@code LivingEntity} only
     * logs one for a CUSTOM-NAMED entity, which a Person stopped being.
     *
     * <p>{@link Burial} writes the log line, the journal entry and the grave out of what any
     * {@code AgentBody} can be asked. Note the ordering: {@code super.die} runs first and drops the
     * inventory, so the grave does not hold one.
     */
    @Override
    public void die(DamageSource cause) {
        super.die(cause);
        Burial.record(this, cause);
    }

    /**
     * Links this freshly created entity to an identity the spawner already registered in the
     * {@link PersonDirectory}, before it is added to the world — so {@link #tick()}'s lazy
     * first-tick creation is skipped and this Person keeps that deliberate (named) identity instead
     * of minting an anonymous random one. Server-side; the path behind {@code /autarkia person spawn}.
     */
    public void assignPerson(AgentId id) {
        setAgentId(id);
    }

    /** Sets this person's identity handle and mirrors it to the synced field, so clients can match
     *  the entity back to its {@link AgentId}. Server-side only — that is where the id originates. */
    private void setAgentId(AgentId id) {
        this.personId = id;
        this.entityData.set(DATA_PERSON_ID, id.value().toString());
    }

    /**
     * This person's directory handle, or {@code null} before it has been assigned. Synced, so it
     * resolves on the client too; resolve names and identity via {@link PersonDirectory}.
     *
     * <p><b>The field first, the synced string only as a fallback.</b> Both are written together
     * and only by {@link #setAgentId}, which loading from disk goes through too, so on the server
     * the field always holds the id. The string is there for the CLIENT, which never runs it.
     *
     * <p>Reaching for the string first cost a fresh {@code UUID} parse per call, several times per
     * being-reading — on the order of a hundred thousand a tick at two hundred agents, with
     * {@code UUID.parse4Nibbles} duly turning up in a server-thread profile.
     */
    public @Nullable AgentId getAgentId() {
        if (this.personId != null) {
            return this.personId;
        }
        String id = this.entityData.get(DATA_PERSON_ID);
        return id.isEmpty() ? null : AgentId.of(UUID.fromString(id));
    }

    // ---- AgentBody: what Anima needs of any body it drives -----------------------------

    /** A Person is its own entity — the escape hatch Anima reads vanilla state through. */
    @Override
    public LivingEntity entity() {
        return this;
    }

    /** {@inheritDoc} Autarkia's name for it is {@link #getAgentId()}. */
    @Override
    public @Nullable AgentId agentId() {
        return getAgentId();
    }

    /** {@inheritDoc} A Person narrates through their {@link Gender}. */
    @Override
    public Pronouns pronouns() {
        return getGender();
    }

    /**
     * What this settler is like: {@link PersonSpecies} read live through
     * {@code config/autarkia.toml} — so {@code /autarkia config reload} retunes a Person
     * mid-stride — plus whatever is currently shifting this particular one.
     *
     * <p>Nothing shifts one yet: with no traits, skills or jobs to hang a modifier on,
     * {@link ModifiedProfile#of} hands back the shared species view unchanged, allocating nothing.
     */
    @Override
    public AgentProfile profile() {
        if (this.profile == null) {
            this.profile = ModifiedProfile.of(AutarkiaConfig.PERSON, modifiers());
        }
        return this.profile;
    }

    /**
     * What frightens a settler — {@link PersonDanger}, read through so the regeneration at every
     * server start (and an operator's edit) reaches a Person already walking around.
     */
    @Override
    public DangerTable danger() {
        return PersonDanger.STORE.get();
    }

    /**
     * What is shifting this Person away from a plain settler.
     *
     * <p>Saved for the modifiers with nowhere else to live — today only the one
     * {@code /anima profile debug} sets by hand. Whatever grows a JOB persists the job and
     * re-applies its modifiers on load; the two compose because modifiers are keyed by id, and
     * re-applying an id replaces rather than stacks.
     */
    @Override
    public AgentModifiers modifiers() {
        if (this.modifiers == null) {
            this.modifiers = new AgentModifiers();
        }
        return this.modifiers;
    }

    @Override
    protected void addAdditionalSaveData(ValueOutput output) {
        super.addAdditionalSaveData(output);
        if (this.personId != null) {
            output.store(TAG_PERSON_ID, UUIDUtil.CODEC, this.personId.value());
        }
        output.store(TAG_INVENTORY, Inventories.CODEC, this.inventory);
        output.putInt(TAG_FOOD_LEVEL, this.metabolism.foodLevel());
        output.putInt(TAG_FOOD_TICK_TIMER, this.metabolism.tickTimer());
        output.putFloat(TAG_FOOD_SATURATION, this.metabolism.saturation());
        output.putFloat(TAG_FOOD_EXHAUSTION, this.metabolism.exhaustion());
        output.putDouble(TAG_COMPANY, this.company.value());
        // The two switches somebody set ON this body, not the working state the driver re-derives.
        // Written unconditionally: "auto is off" has to survive, and so does turning it back on.
        output.putBoolean(TAG_BRAIN_AUTO, this.brain.isAuto());
        output.putBoolean(TAG_BRAIN_WANDER, this.brain.isWander());
        // Read off the field, not modifiers(): the accessor mints an empty set on demand, and a
        // save has no business creating state on a body that never had any.
        if (this.modifiers != null && !this.modifiers.isEmpty()) {
            output.store(TAG_MODIFIERS, Modifiers.LIST, this.modifiers.all());
        }
        // Only while actually going somewhere: ARRIVED and FAILED keep the goal for inspection,
        // and restoring either would send them walking back to a place they are already standing
        // in or have already given up on.
        if (this.navigator.state() == Navigator.State.PATHING
                || this.navigator.state() == Navigator.State.FOLLOWING) {
            output.store(TAG_NAV_WALK, BrainState.WALK, this.navigator.snapshot());
        }
        // Carried between ticks, so it is carried across a reload: a stream that restarts makes
        // the very next roam a different one, and a cooldown that clears is a body that forgave
        // itself while nobody was looking.
        output.putLong(TAG_BRAIN_RANDOM, this.brain.random().state());
        Map<String, Integer> cooldowns = this.brain.cooldowns();
        if (!cooldowns.isEmpty()) {
            output.store(TAG_BRAIN_COOLDOWNS, BrainState.COOLDOWNS, cooldowns);
        }
        // The plan and its grant, as one field. A body mid-errand that came back with an empty
        // executor would re-decide from scratch, which is a reboot it noticed.
        output.store(TAG_BRAIN_PLAN, BrainState.brain(), this.brain.snapshot());
        output.store(TAG_BOARD, AutarkiaTasks.PERSONAL_BOARD, this.personalBoard.snapshot());
        // Losing these does not blank the senses, it makes a body RE-NOTICE everyone around it and
        // announce them again — the loudest way an agent could tell you it had been rebooted.
        output.store(TAG_BEINGS, SenseState.BEINGS, this.beingSense.snapshot());
        // A second copy of lines the archive already holds: the archive is a folder, and the ring
        // `/anima log` reads came back empty after every boot. Bounded by the ring's own cap.
        if (this.personId != null && level() instanceof ServerLevel level) {
            // The two actuators, paired with the task flags that already survived without them: a body
        // that came back "breaking" while its breaker rested waited on a swing nobody was swinging.
        output.store(TAG_SWING, BrainState.SWING, this.blockBreaker.snapshot());
        output.store(TAG_STEP, BrainState.STEP, this.riser.snapshot());
        if (!this.setbacks.isEmpty()) {
            output.store(TAG_SETBACKS, BrainState.SETBACKS, this.setbacks.snapshot());
        }
        this.poiSensor.snapshot().ifPresent(survey ->
                output.store(TAG_SURVEY, BrainState.SURVEY, survey));
        if (!Double.isNaN(this.lastX)) {
            output.putDouble(TAG_LAST_X, this.lastX);
            output.putDouble(TAG_LAST_Z, this.lastZ);
        }
        output.store(TAG_JOURNAL, BrainState.JOURNAL,
                    Journals.of(level.getServer()).snapshot(this.personId));
        }
    }

    @Override
    protected void readAdditionalSaveData(ValueInput input) {
        super.readAdditionalSaveData(input);
        input.read(TAG_PERSON_ID, UUIDUtil.CODEC).map(AgentId::of).ifPresent(this::setAgentId);
        input.read(TAG_INVENTORY, Inventories.CODEC).ifPresent(this.inventory::copyFrom);
        // Vanilla FoodData's own load defaults (full food, 5.0 saturation). Food level must load
        // before saturation — saturation clamps against the current food level.
        this.metabolism.setFoodLevel(input.getIntOr(TAG_FOOD_LEVEL, Metabolism.MAX_FOOD));
        this.metabolism.setTickTimer(input.getIntOr(TAG_FOOD_TICK_TIMER, 0));
        this.metabolism.setSaturation(input.getFloatOr(TAG_FOOD_SATURATION, 5.0F));
        this.metabolism.setExhaustion(input.getFloatOr(TAG_FOOD_EXHAUSTION, 0.0F));
        // read(), not getDoubleOr(): a body saved before this tag existed must be left UNSEEDED so
        // the gauge still starts at its species' band centre. Any default here would be a number
        // for "we don't know", and 0.0 (the obvious one) means desperately lonely.
        input.read(TAG_COMPANY, Codec.DOUBLE).ifPresent(this.company::setValue);
        // Both default ON, which is both the spawn default and what every Person saved before
        // these tags existed should read as.
        this.brain.restoreSwitches(input.getBooleanOr(TAG_BRAIN_AUTO, true),
                input.getBooleanOr(TAG_BRAIN_WANDER, true));
        // applyAll is idempotent per id, so a consumer that later re-applies a job's modifier from
        // its own state lands on the same value rather than stacking a second copy.
        input.read(TAG_MODIFIERS, Modifiers.LIST).ifPresent(modifiers()::applyAll);
        // Held rather than issued: nothing can be pathed here, mid-NBT-read, before the entity is
        // in a world. The first tick hands it over.
        this.pendingWalk = input.read(TAG_NAV_WALK, BrainState.WALK).orElse(null);
        // Absent on a body saved before either existed: the seed the constructor already drew
        // stands, and nobody is on cooldown.
        input.read(TAG_BRAIN_RANDOM, Codec.LONG).ifPresent(this.brain.random()::restore);
        input.read(TAG_BRAIN_COOLDOWNS, BrainState.COOLDOWNS)
                .ifPresent(this.brain::restoreCooldowns);
        this.pendingBrain = input.read(TAG_BRAIN_PLAN, BrainState.brain()).orElse(null);
        this.pendingBoard = input.read(TAG_BOARD, AutarkiaTasks.PERSONAL_BOARD).orElse(null);
        input.read(TAG_BEINGS, SenseState.BEINGS).ifPresent(this.beingSense::restore);
        // Held rather than filed: the journal service belongs to a server this entity has not been
        // added to yet. The first tick hands it over, beside the walk.
        this.pendingJournal = input.read(TAG_JOURNAL, BrainState.JOURNAL).orElse(null);
        input.read(TAG_SWING, BrainState.SWING).ifPresent(this.blockBreaker::restore);
        input.read(TAG_STEP, BrainState.STEP).ifPresent(this.riser::restore);
        input.read(TAG_SETBACKS, BrainState.SETBACKS).ifPresent(this.setbacks::restore);
        input.read(TAG_SURVEY, BrainState.SURVEY).ifPresent(this.poiSensor::restore);
        // NaN is the "never moved yet" marker the field initializer uses, and the right default:
        // the next tick anchors it wherever the body actually stands.
        this.lastX = input.getDoubleOr(TAG_LAST_X, Double.NaN);
        this.lastZ = input.getDoubleOr(TAG_LAST_Z, 0.0);
    }

    /**
     * Drops the entire carried inventory (equipment included) as real items on death, then clears it.
     * Nothing drops twice: {@code Avatar} is a plain {@code LivingEntity}, not a {@code Mob}, so it
     * inherits {@code LivingEntity}'s empty {@code dropEquipment}/{@code dropCustomDeathLoot} — the
     * gear-drop machinery lives in {@code Mob}.
     */
    @Override
    protected void dropCustomDeathLoot(ServerLevel level, DamageSource damageSource, boolean recentlyHit) {
        super.dropCustomDeathLoot(level, damageSource, recentlyHit);
        for (Inventory.Entry entry : this.inventory.occupied()) {
            spawnAtLocation(level, ItemStacks.toVanilla(entry.stack(), registryAccess()));
        }
        this.inventory.clear();
    }

    /**
     * Collects nearby dropped items into the carried inventory — the counterpart to
     * {@link #dropCustomDeathLoot drop-on-death}: a Person gathers loot by walking over it, as a
     * player does. Deliberately <b>not</b> gated on the {@code mobGriefing} gamerule: a Person
     * surviving by picking things up is intended behaviour, not incidental griefing.
     *
     * <p>The scan mirrors vanilla's <b>{@code Player}</b> reach — one block horizontally, half a
     * block vertically ({@code Player.aiStep}'s on-foot box; the flat {@code Mob} looting box has
     * no vertical slack). The half block is not cosmetic: an item on a leaf one block above the
     * feet sits about 0.2 above the head, inside a player's reach and outside a mob's, and fifty
     * Persons pathed forever at a felled log caught in the canopy.
     *
     * <p>Empty stacks and any still within their pickup delay are skipped. Each catch is added to
     * the core {@link Inventory}, losslessly translated; whatever <em>fits</em> leaves the ground
     * stack and flies in on every client ({@link #take}), and a full inventory leaves the item
     * lying there. Items {@code target}ed at a player are not special-cased — {@code ItemEntity}
     * exposes no accessor, and vanilla's own mob looting ignores it too.
     */
    // The pickup sound's pitch is vanilla's own idiom, `(nextFloat() - nextFloat()) * 0.7 + 1`,
    // which spreads the pitch symmetrically around 1. Error Prone reads the two identical calls as
    // one value and concludes the difference is always zero; they are two draws from a mutable
    // generator, so it is not.
    @SuppressWarnings("IdentityBinaryExpression")
    private void pickUpNearbyItems(ServerLevel level) {
        if (!isAlive()) {
            return;
        }
        for (ItemEntity itemEntity :
                level.getEntitiesOfClass(ItemEntity.class, getBoundingBox().inflate(1.0, 0.5, 1.0))) {
            if (itemEntity.isRemoved() || itemEntity.hasPickUpDelay()) {
                continue;
            }
            ItemStack ground = itemEntity.getItem();
            if (ground.isEmpty()) {
                continue;
            }
            int before = ground.getCount();
            dev.luizloyola.anima.core.inv.ItemStack leftover =
                    this.inventory.add(ItemStacks.toCore(ground, registryAccess()));
            int taken = before - leftover.count();
            if (taken <= 0) {
                continue; // inventory full — leave it on the ground
            }
            take(itemEntity, taken);   // the caught portion flies to this Person on every client
            onItemPickup(itemEntity);  // advancement hook, if a player had thrown it
            playSound(SoundEvents.ITEM_PICKUP, 0.2F,
                    ((this.random.nextFloat() - this.random.nextFloat()) * 0.7F + 1.0F) * 2.0F);
            if (leftover.isEmpty()) {
                itemEntity.discard();
            } else {
                // A fresh stack (new identity) so SynchedEntityData re-broadcasts the reduced ground
                // count — shrinking the existing stack in place would not mark the data watcher dirty.
                itemEntity.setItem(ground.copyWithCount(leftover.count()));
            }
        }
    }

    /**
     * This person's external identity as both sides see it — decoded from {@link #DATA_APPEARANCE}
     * and memoised until that string moves. Never null: an unreadable value decodes to
     * {@link Appearance#DEFAULT} rather than failing, so a person with a corrupt record comes back
     * visibly wrong instead of taking the render down with them.
     */
    public Appearance appearance() {
        String raw = this.entityData.get(DATA_APPEARANCE);
        if (!raw.equals(this.appearanceRaw)) {
            this.appearanceRaw = raw;
            this.appearance = Appearance.decode(raw);
            this.appearanceRecipe = AppearanceComposer.compose(this.appearance,
                    PersonAppearance.catalog(), PersonAppearance::has);
        }
        return this.appearance;
    }

    /**
     * What to bake to draw this person — Anima's currency, composed from {@link #appearance()} by
     * Autarkia's own composer. Today one whole-canvas part naming a vanilla skin, and the client
     * renders through the bake either way. The same object comes back until the synced appearance
     * moves, so {@code ClientPerson}'s texture handle recognises "still the same look" without
     * hashing a recipe every frame.
     */
    public Recipe appearanceRecipe() {
        appearance();
        return this.appearanceRecipe;
    }

    /** This person's synced gender (readable on both sides). Part of the external identity. */
    public Gender getGender() {
        return appearance().gender();
    }

    /** Whether this person renders with the slim (Alex) arm model. Synced; used by the renderer. */
    public boolean isSlim() {
        return appearance().model() == ModelType.SLIM;
    }

    /**
     * Client-only: forces a glowing outline regardless of any real (synced) glow, driven each tick by
     * the debug selection highlight ({@code mod.client.DebugGlow}) and coloured black via
     * {@link #getTeamColor()}. Never set server-side, so nothing is persisted or synced.
     */
    private boolean forcedGlow;

    /** Client-only glow toggle — see {@link #forcedGlow}. */
    public void setForcedGlow(boolean forcedGlow) {
        this.forcedGlow = forcedGlow;
    }

    /**
     * Overridden so the client can highlight a person locally: on 26.1 the render pipeline gates the
     * outline on this (via {@code Minecraft.shouldEntityAppearGlowing}). {@link #forcedGlow} is only
     * ever true on the client, so the server path is unchanged.
     */
    @Override
    public boolean isCurrentlyGlowing() {
        return this.forcedGlow || super.isCurrentlyGlowing();
    }

    /**
     * Colours the {@link #forcedGlow debug} outline black — the render pipeline reads the outline
     * colour from this. Only matters on the client while force-glowing (never set server-side);
     * otherwise the vanilla team colour stands.
     */
    @Override
    public int getTeamColor() {
        return this.forcedGlow ? 0x000000 : super.getTeamColor();  
    }

    /**
     * Supplies the game-profile {@link Avatar} exposes on 26.1. Unused for rendering — the visible
     * skin is baked from {@link #appearanceRecipe()} on the client, not read off this profile. No
     * {@code @Override}: pre-26.1 {@code Avatar} has no such method, where this is harmless dead code.
     */
    public ResolvableProfile getProfile() {
        return Mannequin.DEFAULT_PROFILE;
    }

    /**
     * Keeps a Person's size — and with it the nametag position, which vanilla derives from box
     * height — unchanged through death, like a real player. {@link Avatar}'s {@code Pose.DYING}
     * otherwise collapses to a fixed 0.2×0.2 box for the death animation; combined with a
     * Person's always-on label ({@link #applyIdentity}) that dragged the nametag down to the
     * ground the moment death started.
     */
    @Override
    public EntityDimensions getDefaultDimensions(Pose pose) {
        return pose == Pose.DYING ? super.getDefaultDimensions(Pose.STANDING) : super.getDefaultDimensions(pose);
    }
}
