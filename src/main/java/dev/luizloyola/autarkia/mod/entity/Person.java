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
import dev.luizloyola.anima.core.appearance.Part;
import dev.luizloyola.anima.core.appearance.Recipe;
import dev.luizloyola.autarkia.core.person.Appearance;
import dev.luizloyola.autarkia.core.person.AppearanceComposer;
import dev.luizloyola.autarkia.core.person.Look;
import dev.luizloyola.autarkia.core.person.Gender;
import dev.luizloyola.autarkia.core.person.ModelType;
import dev.luizloyola.anima.core.agent.Needs;
import dev.luizloyola.anima.core.agent.AgentId;
import dev.luizloyola.autarkia.core.person.PersonDanger;
import dev.luizloyola.autarkia.core.person.PersonIdentity;
import dev.luizloyola.autarkia.core.person.PersonSkins;
import dev.luizloyola.autarkia.core.person.PersonSpecies;
import dev.luizloyola.autarkia.mod.AutarkiaMod;
import dev.luizloyola.anima.mod.brain.BrainDriver;
import dev.luizloyola.anima.core.brain.board.WorkSource;
import dev.luizloyola.anima.core.social.PartyId;
import dev.luizloyola.anima.mod.social.PartyData;
import dev.luizloyola.autarkia.core.board.ComposedBoards;
import dev.luizloyola.autarkia.core.board.KeepStocked;
import dev.luizloyola.autarkia.core.board.PersonalBoard;
import dev.luizloyola.autarkia.core.board.Stock;
import dev.luizloyola.autarkia.mod.board.PartyBoards;
import dev.luizloyola.anima.mod.brain.AgentBlockBreaker;
import dev.luizloyola.anima.mod.brain.AgentRiser;
import dev.luizloyola.anima.mod.brain.PoiSensor;
import dev.luizloyola.autarkia.mod.inv.PersonContainer;
import dev.luizloyola.autarkia.mod.inv.PersonInventoryMenu;
import dev.luizloyola.anima.mod.log.Journals;
import dev.luizloyola.anima.mod.nav.Navigator;
import dev.luizloyola.autarkia.mod.person.PersonDirectory;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.chat.Component;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.InteractionResult;
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
import dev.luizloyola.anima.mod.body.AgentBody;
import dev.luizloyola.anima.mod.body.Modifiers;
import dev.luizloyola.anima.mod.identity.AgentRecords;
import dev.luizloyola.anima.mod.identity.Graves;
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
     * Default skin, as a texture <em>asset id</em> — vanilla's own Steve, which every client
     * already has. Autarkia ships no skin textures at all; see {@link PersonSkins} for why.
     */
    public static final Identifier DEFAULT_SKIN = Identifier.parse(PersonSkins.DEFAULT_SKIN);

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
    /** Where somebody last told this body to walk — see {@link #pendingGoal}. */
    private static final String TAG_NAV_GOAL = "NavGoal";

    private static final String TAG_FOOD_LEVEL = "foodLevel";
    private static final String TAG_FOOD_TICK_TIMER = "foodTickTimer";
    private static final String TAG_FOOD_SATURATION = "foodSaturationLevel";
    private static final String TAG_FOOD_EXHAUSTION = "foodExhaustionLevel";

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
    private @Nullable BlockPos pendingGoal;

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
     * This person's own board — where wants stated about this body live, and nobody else can reach.
     * A project posted here is theirs for life (compose, don't merge — see {@link ComposedBoards}).
     */
    private final PersonalBoard personalBoard = personalBoard(getId());

    /**
     * This person's brain host ({@link BrainDriver}) — a machine beside the {@link #navigator}: it
     * runs the task executor and only ever <em>reads</em> the body. Transient — a running task is
     * working state, not persisted; a reload just re-decides.
     */
    private final BrainDriver brain = new BrainDriver(this, new ComposedBoards(
            personalBoard.viewFor(this::getAgentId), this::partyWork));

    /**
     * The standing stock rule every fresh settler wants: keep this many logs, at this priority.
     * Autarkia's to own — Anima has no opinion about what an agent should stockpile.
     */
    private static final int STOCK_LOGS = 16;
    private static final double STOCK_PRIORITY = 0.35;

    /**
     * The party board view handed to the brain, and the party it was built for — re-checked every
     * ask, rebuilt only when membership moves, since a view cached for life would quietly keep
     * serving a board they no longer belong to.
     */
    private @Nullable PartyId boardParty;
    private @Nullable WorkSource partyWork;

    /** A fresh settler's own board, carrying the one standing want everybody starts with. */
    private static PersonalBoard personalBoard(int offset) {
        PersonalBoard board = new PersonalBoard();
        board.post(new KeepStocked(Stock.LOGS, STOCK_LOGS, STOCK_PRIORITY, offset));
        return board;
    }

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

    /**
     * This person's need levels ({@link Needs}) — body state beside the {@link #inventory}, not a
     * brain organ: the entity owns and ticks its own metabolism, as vanilla's {@code FoodData}
     * belongs to the player rather than to any AI, and the brain only ever <em>reads</em> it.
     * Persisted in this entity's NBT (see {@link #TAG_FOOD_LEVEL}), ticked by {@link #tickNeeds()}.
     */
    private final Needs needs = new Needs();
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
        // matching a player's walk means matching MOVEMENT_SPEED (0.1) — verified in-world within
        // ~2%. STEP_HEIGHT stays at the living default (0.6, player-equal).
        return LivingEntity.createLivingAttributes()
                .add(Attributes.MOVEMENT_SPEED, 0.1);
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
        if (this.pendingGoal != null && this.level() instanceof ServerLevel) {
            BlockPos goal = this.pendingGoal;
            this.pendingGoal = null; // one attempt; a failed path is the navigator's to report
            this.navigator.pathTo(goal);
            journal().record(Category.BODY, "resumed", "walking to " + goal.toShortString());
        }
        super.tick();
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
        // Buoyancy last: it owns the vertical input while submerged, whatever drove the horizontal.
        floatInWater();
    }

    /**
     * Constant survival reflex: while any part of the body is in water, hold the swim-up input —
     * float, never drown. Owned by the body rather than the {@link Navigator} so a Person who
     * wandered in, was shoved in, or is idle floats exactly like one crossing on a path. Runs after
     * the navigator so it wins the vertical input, and is effective same-tick because {@code aiStep}
     * reads {@code this.jumping} right after {@code serverAiStep} (see {@link #driveJump}).
     */
    private void floatInWater() {
        if (isInWater()) {
            setJumping(true); // held-jump-in-water rises via aiStep's jumpInLiquid
        }
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

    /** This person's need levels — body state the (future) brain reads, never owns. See {@link #needs}. */
    public Needs needs() {
        return this.needs;
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
     *   <li><b>Movement exhaustion</b> — 0.1/m sprinting on ground, 0.01/m swimming, walking free,
     *       measured against last tick's position.</li>
     *   <li><b>Regen/starvation inputs</b> — the {@code naturalRegeneration} gamerule and hurt-ness.
     *       {@code isHurt()} is Player-only on 26.1.2, so its body is inlined here: alive and below
     *       max health.</li>
     *   <li><b>Effects</b> — core decides <em>what</em> happens ({@link Needs.TickResult}), the body
     *       applies it: a half-heart heal, a starvation hit on vanilla's 80-tick cadence. Unlike
     *       vanilla the starvation damage is not difficulty-clamped — starvation must be a real
     *       cause of death.</li>
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
                    this.needs.exhaust(Needs.EXHAUSTION_SWIM_PER_METER * meters);
                } else if (isSprinting() && onGround()) {
                    this.needs.exhaust(Needs.EXHAUSTION_SPRINT_PER_METER * meters);
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
            this.needs.exhaust(0.005F * (hungerEffect.getAmplifier() + 1));
        }
        MobEffectInstance saturationEffect = getEffect(MobEffects.SATURATION);
        if (saturationEffect != null) {
            int nutrition = saturationEffect.getAmplifier() + 1;
            this.needs.eat(nutrition, Needs.saturationByModifier(nutrition, 1.0F));
        }
        boolean naturalRegen =
                ((ServerLevel) level()).getGameRules().get(GameRules.NATURAL_HEALTH_REGENERATION);
        boolean isHurt = getHealth() > 0.0F && getHealth() < getMaxHealth();
        Needs.TickResult result = this.needs.tick(naturalRegen, isHurt);
        if (result.heal() > 0.0F) {
            heal(result.heal());
        }
        if (result.starve()) {
            // hurtServer, not the deprecated side-dispatching hurt(): only ever ticked from
            // serverAiStep, so the level is always the server one (same cast as the Navigator).
            hurtServer((ServerLevel) level(), damageSources().starve(), 1.0F);
        }
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
            this.needs.exhaust(isSprinting() ? Needs.EXHAUSTION_SPRINT_JUMP : Needs.EXHAUSTION_JUMP);
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
     * by a live {@link PersonContainer} over the core inventory. Server-authoritative; the client
     * predicts success so the arm swings.
     *
     * <p>Driven from a Fabric {@code UseEntityCallback} rather than a vanilla {@code interact}
     * override, whose signature drifts across versions — a plain method keeps this {@code mod} class
     * version-neutral. The callback does the empty-hand/main-hand gating.
     */
    public InteractionResult openInventory(Player player) {
        if (!this.level().isClientSide()) {
            player.openMenu(new SimpleMenuProvider(
                    (syncId, playerInv, opener) ->
                            new PersonInventoryMenu(syncId, playerInv, new PersonContainer(this), getId()),
                    getName()));
        }
        return InteractionResult.SUCCESS;
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
        this.needs.exhaust(source.getFoodExhaustion());
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
        setJumping(false);
        // A navigator that stops mid-sprint must not leave the ×1.3 modifier latched for its
        // next plain walk.
        if (isSprinting()) {
            setSprinting(false);
        }
    }

    /**
     * Movement control: choose this tick's gait. Sprinting adds vanilla's ×1.3 speed modifier
     * (picked up by {@link #driveForward}'s attribute read — call this first) and the sprint-jump
     * boost in {@code jumpFromGround}. Guarded on change: {@code setSprinting} churns an attribute
     * modifier.
     *
     * <p>Enabling is subject to the vanilla food-6 sprint gate ({@link Needs#canSprint()});
     * disabling is always allowed. A food&le;6 Person therefore cannot sprint, so 3-gap leap paths
     * fail — the pathfinder doesn't know yet (deferred: hunger-aware {@code MoveCapabilities}).
     */
    public void driveSprint(boolean sprint) {
        if (sprint && !this.needs.canSprint()) {
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
     * Snap body and head to face {@code heading} (degrees), pitch pinned flat (0°) so the gaze stays
     * at eye level rather than tilting at the ground — without the reset the head kept whatever
     * downward pitch it spawned or loaded with. Pitch is render-only for a walking entity
     * ({@code travel} steers by yaw alone), so leveling it never affects motion.
     */
    private void face(float heading) {
        setYRot(heading);
        setYHeadRot(heading);
        this.yBodyRot = heading;
        setXRot(0.0F);
    }

    /**
     * Turn the gaze onto the centre of {@code cell} — head, eyes, and, unless the cell is straight
     * underfoot, body. The one shared answer to "look at that block" for every arm actuator
     * (breaker, placer), so none re-derives the trig. A cell in their own column, underfoot or
     * overhead, has no meaningful bearing (horizontal distance ~0), so the travel yaw is kept and
     * only the pitch tilts.
     */
    public void faceBlock(BlockPos cell) {
        Vec3 center = Vec3.atCenterOf(cell);
        Vec3 eye = getEyePosition();
        double dx = center.x - eye.x;
        double dy = center.y - eye.y;
        double dz = center.z - eye.z;
        setXRot((float) -Math.toDegrees(Math.atan2(dy, Math.sqrt(dx * dx + dz * dz))));
        BlockPos self = blockPosition();
        if (cell.getX() != self.getX() || cell.getZ() != self.getZ()) {
            float yaw = (float) Math.toDegrees(Math.atan2(dz, dx)) - 90.0F;
            setYRot(yaw);
            setYHeadRot(yaw);
            this.yBodyRot = yaw;
        }
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
        // then points at a texture no longer in the jar. Corrected once on first load rather than
        // resolved on every read. Only a literal skin goes stale — a composed look names catalog
        // entries, which fall back on their own.
        if (identityAppearance.look() instanceof Look.Skin worn) {
            String resolved = PersonSkins.resolve(worn.assetId(), identityAppearance.gender());
            if (!resolved.equals(worn.assetId()) && level() instanceof ServerLevel serverLevel) {
                identityAppearance = identityAppearance.withLook(new Look.Skin(resolved));
                PersonDirectory.get(serverLevel.getServer())
                        .replace(identity.withAppearance(identityAppearance));
            }
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
     * Records the death the way vanilla records a player's, because vanilla will not: a Person is not
     * a {@code ServerPlayer}, so no death message is broadcast, and {@code LivingEntity} logs one only
     * for a CUSTOM-NAMED entity — which a Person stopped being when the name left entity data. Server
     * log plus the person's own journal; nothing goes to chat.
     */
    @Override
    public void die(DamageSource cause) {
        super.die(cause);
        if (!(level() instanceof ServerLevel level)) {
            return;
        }
        // The combat tracker's line already names them ("Alice starved to death"), so it is
        // logged as-is rather than prefixed with the name a second time.
        String story = getCombatTracker().getDeathMessage().getString();
        AutarkiaMod.LOGGER.info("{}", story);
        journal().record(Category.BODY, "death", story);
        bury(level, story);
    }

    /**
     * Writes the death down and lets go of everything a dead mind cannot use.
     *
     * <p><b>Here or nowhere:</b> afterwards nothing can tell this settler died — a directory entry
     * with no loaded body is what one in an unloaded chunk looks like — and no
     * {@code RemovalReason} stands in ({@code CHANGED_DIMENSION} fires for a portal, the two
     * {@code UNLOADED_} ones mean the opposite of dead). At {@code die()} rather than at removal
     * because a body lingers through its death animation; {@code Graves} makes it idempotent.
     *
     * <p>Let go: knowledge and party membership (a dead member makes a party larger than it is, and
     * boards count members). Identity, the contact books naming them and their journal stay.
     */
    private void bury(ServerLevel level, String story) {
        AgentId id = this.personId;
        if (id == null) {
            return; // died before anybody decided who they were; there is nothing to bury
        }
        MinecraftServer server = level.getServer();
        boolean news = Graves.get(server).bury(id, new Graves.Death(level.getGameTime(),
                level.dimension().identifier().toString(),
                getBlockX(), getBlockY(), getBlockZ(), story));
        if (news) {
            AgentRecords.bury(server, id);
        }
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
     * {@code config/autarkia.json} — so {@code /autarkia config reload} retunes a Person
     * mid-stride — plus whatever is currently shifting this particular one.
     *
     * <p>Nothing shifts one yet (no traits, skills or jobs), so {@link ModifiedProfile#of} hands
     * back the shared species view unchanged, allocating nothing; {@code /autarkia profile} shows
     * the derivation either way.
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
        output.putInt(TAG_FOOD_LEVEL, this.needs.foodLevel());
        output.putInt(TAG_FOOD_TICK_TIMER, this.needs.tickTimer());
        output.putFloat(TAG_FOOD_SATURATION, this.needs.saturation());
        output.putFloat(TAG_FOOD_EXHAUSTION, this.needs.exhaustion());
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
        BlockPos goal = this.navigator.goal();
        if (goal != null && (this.navigator.state() == Navigator.State.PATHING
                || this.navigator.state() == Navigator.State.FOLLOWING)) {
            output.store(TAG_NAV_GOAL, BlockPos.CODEC, goal);
        }
    }

    @Override
    protected void readAdditionalSaveData(ValueInput input) {
        super.readAdditionalSaveData(input);
        input.read(TAG_PERSON_ID, UUIDUtil.CODEC).map(AgentId::of).ifPresent(this::setAgentId);
        input.read(TAG_INVENTORY, Inventories.CODEC).ifPresent(this.inventory::copyFrom);
        // Vanilla FoodData's own load defaults (full food, 5.0 saturation). Food level must load
        // before saturation — saturation clamps against the current food level.
        this.needs.setFoodLevel(input.getIntOr(TAG_FOOD_LEVEL, Needs.MAX_FOOD));
        this.needs.setTickTimer(input.getIntOr(TAG_FOOD_TICK_TIMER, 0));
        this.needs.setSaturation(input.getFloatOr(TAG_FOOD_SATURATION, 5.0F));
        this.needs.setExhaustion(input.getFloatOr(TAG_FOOD_EXHAUSTION, 0.0F));
        // Both default ON, which is both the spawn default and what every Person saved before
        // these tags existed should read as.
        this.brain.restoreSwitches(input.getBooleanOr(TAG_BRAIN_AUTO, true),
                input.getBooleanOr(TAG_BRAIN_WANDER, true));
        // applyAll is idempotent per id, so a consumer that later re-applies a job's modifier from
        // its own state lands on the same value rather than stacking a second copy.
        input.read(TAG_MODIFIERS, Modifiers.LIST).ifPresent(modifiers()::applyAll);
        // Held rather than issued: nothing can be pathed here, mid-NBT-read, before the entity is
        // in a world. The first tick hands it over.
        this.pendingGoal = input.read(TAG_NAV_GOAL, BlockPos.CODEC).orElse(null);
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
            this.appearanceRecipe = AppearanceComposer.compose(this.appearance);
        }
        return this.appearance;
    }

    /**
     * What to bake to draw this person — Anima's currency, composed from {@link #appearance()} by
     * Autarkia's own composer. Today it is one whole-canvas part naming a vanilla skin, so the bake
     * is that PNG unchanged; the renderer already goes through here so that stops being true
     * without anything downstream noticing.
     */
    public Recipe appearanceRecipe() {
        appearance();
        return this.appearanceRecipe;
    }

    public Identifier getSkinTexture() {
        List<Part> statics = appearanceRecipe().statics();
        return statics.isEmpty() ? DEFAULT_SKIN : Identifier.parse(statics.get(0).texture());
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
     * skin comes from {@link #getSkinTexture()} via the renderer, not from this profile. No
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
