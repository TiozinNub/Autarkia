package dev.luizloyola.autarkia.mod.entity;

import dev.luizloyola.autarkia.compat.inv.Inventories;
import dev.luizloyola.autarkia.compat.inv.ItemStacks;
import dev.luizloyola.autarkia.core.inv.ArmorType;
import dev.luizloyola.autarkia.core.inv.Inventory;
import dev.luizloyola.autarkia.core.person.Appearance;
import dev.luizloyola.autarkia.core.person.Gender;
import dev.luizloyola.autarkia.core.person.ModelType;
import dev.luizloyola.autarkia.core.person.Needs;
import dev.luizloyola.autarkia.core.person.PersonId;
import dev.luizloyola.autarkia.core.person.PersonIdentity;
import dev.luizloyola.autarkia.mod.brain.BrainDriver;
import dev.luizloyola.autarkia.mod.inv.PersonContainer;
import dev.luizloyola.autarkia.mod.inv.PersonInventoryMenu;
import dev.luizloyola.autarkia.mod.nav.Navigator;
import dev.luizloyola.autarkia.mod.person.PersonDirectory;
import java.util.UUID;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.chat.Component;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
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
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ResolvableProfile;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * A player-shaped autonomous NPC. Extends {@link Avatar} so it renders like a player (custom skin,
 * player model) without inheriting the immovable "decoration" behaviour of {@link Mannequin}.
 */
public class Person extends Avatar {
    /**
     * Default skin, as a texture <em>asset id</em> — not a file path. It resolves to
     * {@code assets/autarkia/textures/entity/person/default.png} (the render pipeline prepends
     * {@code textures/} and appends {@code .png}). Redirect any Person via {@link #setSkinTexture}.
     */
    public static final Identifier DEFAULT_SKIN =
            Identifier.fromNamespaceAndPath("autarkia", "entity/person/default");

    private static final EntityDataAccessor<String> DATA_SKIN =
            SynchedEntityData.defineId(Person.class, EntityDataSerializers.STRING);

    /**
     * External identity synced to clients for rendering (the always-on tier). Skin lives in
     * {@link #DATA_SKIN}; gender here. Both are projected from the person's {@link Appearance} in
     * the {@link PersonDirectory} — the directory is the source of truth, these are its client
     * mirror. The full identity (name, …) is not synced.
     */
    private static final EntityDataAccessor<String> DATA_GENDER =
            SynchedEntityData.defineId(Person.class, EntityDataSerializers.STRING);

    /** Whether this person renders with the slim (Alex) arm model rather than wide (Steve). Synced
     *  for the renderer; projected from the person's {@link Appearance#model()}. */
    private static final EntityDataAccessor<Boolean> DATA_SLIM =
            SynchedEntityData.defineId(Person.class, EntityDataSerializers.BOOLEAN);

    /**
     * This person's identity handle ({@link PersonId}) as a UUID string, synced so the client can
     * match a rendered entity back to a {@code PersonId} (e.g. the debug wand's glow). Empty until
     * the server assigns one; identity <em>content</em> (name, …) stays server-side.
     */
    private static final EntityDataAccessor<String> DATA_PERSON_ID =
            SynchedEntityData.defineId(Person.class, EntityDataSerializers.STRING);

    private static final String TAG_PERSON_ID = "PersonId";

    /** NBT key under which this entity persists its carried inventory (see {@link Inventories#CODEC}). */
    private static final String TAG_INVENTORY = "Inventory";

    /**
     * NBT keys for this entity's food state — vanilla {@code FoodData}'s exact four tags (names,
     * types and load defaults verified against the 26.1.2 bytecode), so a Person's food reads
     * familiarly in NBT tooling. Hunger dies with the body; durable memories are instead
     * {@link PersonId}-keyed, per the brain design's storage section.
     */
    private static final String TAG_FOOD_LEVEL = "foodLevel";
    private static final String TAG_FOOD_TICK_TIMER = "foodTickTimer";
    private static final String TAG_FOOD_SATURATION = "foodSaturationLevel";
    private static final String TAG_FOOD_EXHAUSTION = "foodExhaustionLevel";

    /** Whether this load has projected the directory identity onto the synced fields yet. */
    private boolean identityProjected;

    /**
     * This entity's link to its identity in the world-scoped {@link PersonDirectory} — only a
     * reference, so the identity itself outlives the entity. {@code null} until first assigned.
     */
    private @Nullable PersonId personId;

    /**
     * Debug movement harness for tuning locomotion: while {@code true}, {@link #serverAiStep()}
     * drives this person straight forward, sprinting and auto-jumping, at a player's jump-sprint
     * speed. Toggled by shift-right-clicking the debug wand ({@code mod.item.DebugWandItem}).
     * Transient: movement is server-authoritative, so nothing is saved or synced.
     */
    private boolean debugRunForward;

    /**
     * Drives this person toward a target position (see {@link #serverAiStep()} /
     * {@link #navigateTo}) — the seam the pathfinder feeds. Transient: movement is
     * server-authoritative and not persisted across reloads.
     */
    private final Navigator navigator = new Navigator(this);

    /**
     * This person's brain host ({@link BrainDriver}) — a machine beside the {@link #navigator}: it
     * runs the task executor and only ever <em>reads</em> the body. Transient — a running task is
     * working state, not persisted; a reload just re-decides.
     */
    private final BrainDriver brain = new BrainDriver(this);

    /**
     * This person's need levels ({@link Needs}) — body state beside the {@link #inventory}, not a
     * brain organ: the entity owns and ticks its own metabolism, as vanilla's {@code FoodData}
     * belongs to the player rather than to any AI, and the brain only ever <em>reads</em> it.
     * Persisted in this entity's NBT (see {@link #TAG_FOOD_LEVEL}), ticked by {@link #tickNeeds()}.
     */
    private final Needs needs = new Needs();

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
    private final java.util.EnumMap<EquipmentSlot, dev.luizloyola.autarkia.core.inv.ItemStack> mirroredEquipment =
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
        builder.define(DATA_SKIN, DEFAULT_SKIN.toString());
        builder.define(DATA_GENDER, Gender.MALE.name());
        builder.define(DATA_SLIM, false);
        builder.define(DATA_PERSON_ID, "");
    }

    @Override
    public void tick() {
        super.tick();
        if (this.level() instanceof ServerLevel serverLevel
                && (this.personId == null || !this.identityProjected)) {
            PersonDirectory directory = PersonDirectory.get(serverLevel.getServer());
            // Assign a persistent identity on first tick. Lazy (not in a constructor) because it
            // needs the running server; Avatar is not a Mob, so there is no finalizeSpawn hook.
            if (this.personId == null) {
                setPersonId(directory.createPerson().id());
            }
            // Mirror the public appearance onto the synced fields, once per load — the directory is
            // the source of truth.
            if (!this.identityProjected) {
                directory.find(this.personId).ifPresent(this::applyIdentity);
                this.identityProjected = true;
            }
        }
    }

    @Override
    protected void serverAiStep() {
        super.serverAiStep();
        syncEquipmentMirror();
        // Metabolism runs every server tick no matter who owns the movement input below: food
        // burns (and starvation bites) whether she is navigating, debug-sprinting, or idle.
        tickNeeds();
        // The brain decides first, then the Navigator (below) executes locomotion the same tick.
        this.brain.tick();
        if (this.debugRunForward) {
            // getAttributeValue includes the sprint modifier applied by setSprinting, so this is
            // already the ×1.3 sprint speed; the 0.98 damping keeps it exact vs a player.
            this.setSpeed((float) this.getAttributeValue(Attributes.MOVEMENT_SPEED));
            this.zza = PLAYER_INPUT_DAMPING; // forward along the current yRot; no strafe
            // Auto-jump: aiStep's grounded jumpFromGround() adds vanilla's own 0.2 sprint-jump
            // boost while sprinting, so no manual momentum. Set here because aiStep runs
            // serverAiStep before its jump check — the press lands the same tick.
            this.setJumping(true);
        } else {
            // The navigator owns the forward input the rest of the time: it walks toward a target
            // when it has one, and holds the input at rest otherwise.
            this.navigator.tick();
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

    /** This person's need levels — body state the (future) brain reads, never owns. See {@link #needs}. */
    public Needs needs() {
        return this.needs;
    }

    /**
     * One tick of metabolism, from {@link #serverAiStep()} — the body's counterpart of vanilla
     * {@code FoodData.tick(ServerPlayer)}:
     *
     * <ol>
     *   <li><b>Sprint exhaustion</b> — vanilla's 0.1/m on ground (walking is free), measured
     *       against last tick's position. Swim exhaustion is skipped: Persons can't swim yet.</li>
     *   <li><b>Regen/starvation inputs</b> — the {@code naturalRegeneration} gamerule (26.1 keeps it
     *       on {@code ServerLevel}; the cast is safe, this only ticks from serverAiStep) and
     *       hurt-ness, {@code isHurt()} being Player-only on 26.1.2: alive and below max health.</li>
     *   <li><b>Effects</b> — core decides ({@link Needs.TickResult}), the body applies: a half-heart
     *       on regen, a starvation hit on vanilla's 80-tick cadence, not difficulty-clamped because
     *       population dynamics needs starvation to be a real cause of death.</li>
     * </ol>
     */
    private void tickNeeds() {
        boolean firstSample = Double.isNaN(this.lastX);
        if (!firstSample && isSprinting() && onGround()) {
            double dx = getX() - this.lastX;
            double dz = getZ() - this.lastZ;
            float meters = (float) Math.sqrt(dx * dx + dz * dz);
            if (meters > 0.0F) {
                this.needs.exhaust(Needs.EXHAUSTION_SPRINT_PER_METER * meters);
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
        dev.luizloyola.autarkia.core.inv.ItemStack coreStack = this.inventory.get(coreSlot);
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
        dev.luizloyola.autarkia.core.inv.ItemStack entityAsCore = ItemStacks.toCore(entityStack, registryAccess());
        this.inventory.set(coreSlot, entityAsCore);
        this.mirroredEquipment.put(slot, entityAsCore);
        this.mirroredVanilla.put(slot, entityStack.copy());
    }

    /**
     * Send this person to the cell containing {@code target} (world coordinates): the
     * {@link Navigator} computes a route (off the main thread) and walks it. Cancels the debug
     * jump-sprinter first — the two both own the forward input, so only one may drive at a time.
     */
    public void navigateTo(Vec3 target) {
        if (this.debugRunForward) {
            this.debugRunForward = false;
            setSprinting(false);
        }
        this.navigator.pathTo(net.minecraft.core.BlockPos.containing(target));
    }

    /**
     * Right-click opens this Person's inventory as a container screen (all 41 slots, take/put),
     * backed by a live {@link PersonContainer} over the core inventory, so edits write through to
     * the source of truth. Server-authoritative; the client predicts success so the arm swings.
     * Main hand only.
     */
    @Override
    public InteractionResult interact(Player player, InteractionHand hand, Vec3 hitPos) {
        // A held item takes precedence — vanilla calls entity.interact() before the item's
        // interactLivingEntity, so we must step aside (PASS) to let e.g. the debug wand
        // select/command the Person.
        if (hand != InteractionHand.MAIN_HAND || !player.getItemInHand(hand).isEmpty()) {
            return super.interact(player, hand, hitPos);
        }
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
        super.actuallyHurt(level, source, amount);
        doHurtEquipment(source, amount,
                EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET);
        this.needs.exhaust(source.getFoodExhaustion());
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
        // Not for the debug sprinter (which never routes through here): a navigator that stops
        // mid-sprint must not leave the ×1.3 modifier latched for its next plain walk.
        if (!this.debugRunForward && isSprinting()) {
            setSprinting(false);
        }
    }

    /**
     * Movement control: choose this tick's gait. Sprinting adds vanilla's ×1.3 speed modifier
     * (picked up by {@link #driveForward}'s attribute read — call this first) and the sprint-jump
     * boost. Guarded on change, since {@code setSprinting} churns an attribute modifier.
     *
     * <p>Enabling is subject to the vanilla food-6 gate ({@link Needs#canSprint()}); disabling is
     * always allowed. A food&le;6 Person therefore cannot sprint, so 3-gap leap paths fail — the
     * pathfinder doesn't know yet (deferred: hunger-aware {@code AgentProfile}).
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
     * Debug harness toggle: flips the straight-line jump-sprinter (see {@link #debugRunForward}) and
     * enables sprinting ({@link #setSprinting} → vanilla's ×1.3 MOVEMENT_SPEED modifier);
     * {@link #serverAiStep()} drives the forward input and the auto-jump. Both are vanilla's, so it
     * matches a player's jump-sprint exactly. On enable the person snaps to face {@code heading}.
     */
    public boolean toggleDebugWalk(float heading) {
        this.debugRunForward = !this.debugRunForward;
        this.setSprinting(this.debugRunForward);
        if (this.debugRunForward) {
            this.navigator.stop(); // the sprinter takes the forward input over from any active nav
            face(heading);
        } else {
            this.zza = 0.0F;
        }
        return this.debugRunForward;
    }

    /**
     * Push a person's public identity onto the synced fields (server-side). The name rides on the
     * auto-synced custom name, always visible, so every client that sees this Person reads it.
     */
    private void applyIdentity(PersonIdentity identity) {
        setCustomName(Component.literal(identity.name()));
        setCustomNameVisible(true);
        Appearance appearance = identity.appearance();
        setSkinTexture(Identifier.parse(appearance.skin()));
        this.entityData.set(DATA_GENDER, appearance.gender().name());
        this.entityData.set(DATA_SLIM, appearance.model() == ModelType.SLIM);
    }

    /**
     * Links this freshly created entity to an identity the spawner already registered in the
     * {@link PersonDirectory}, before it is added to the world — so {@link #tick()}'s lazy
     * first-tick creation is skipped and this Person keeps that deliberate (named) identity instead
     * of minting an anonymous random one. Server-side; the path behind {@code /autarkia person spawn}.
     */
    public void assignPerson(PersonId id) {
        setPersonId(id);
    }

    /** Sets this person's identity handle and mirrors it to the synced field, so clients can match
     *  the entity back to its {@link PersonId}. Server-side only — that is where the id originates. */
    private void setPersonId(PersonId id) {
        this.personId = id;
        this.entityData.set(DATA_PERSON_ID, id.value().toString());
    }

    /** This person's directory handle, or {@code null} before it has been assigned (the spawn tick
     *  has not run yet). Synced, so it resolves on the client too. Resolve names/identity via
     *  {@link PersonDirectory}. */
    public @Nullable PersonId getPersonId() {
        String id = this.entityData.get(DATA_PERSON_ID);
        return id.isEmpty() ? null : PersonId.of(UUID.fromString(id));
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
    }

    @Override
    protected void readAdditionalSaveData(ValueInput input) {
        super.readAdditionalSaveData(input);
        input.read(TAG_PERSON_ID, UUIDUtil.CODEC).map(PersonId::of).ifPresent(this::setPersonId);
        input.read(TAG_INVENTORY, Inventories.CODEC).ifPresent(this.inventory::copyFrom);
        // Vanilla FoodData's own load defaults (full food, 5.0 saturation). Food level must load
        // before saturation — saturation clamps against the current food level.
        this.needs.setFoodLevel(input.getIntOr(TAG_FOOD_LEVEL, Needs.MAX_FOOD));
        this.needs.setTickTimer(input.getIntOr(TAG_FOOD_TICK_TIMER, 0));
        this.needs.setSaturation(input.getFloatOr(TAG_FOOD_SATURATION, 5.0F));
        this.needs.setExhaustion(input.getFloatOr(TAG_FOOD_EXHAUSTION, 0.0F));
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

    public Identifier getSkinTexture() {
        return Identifier.parse(this.entityData.get(DATA_SKIN));
    }

    public void setSkinTexture(Identifier id) {
        this.entityData.set(DATA_SKIN, id.toString());
    }

    /** This person's synced gender (readable on both sides). Part of the external identity. */
    public Gender getGender() {
        return Gender.valueOf(this.entityData.get(DATA_GENDER));
    }

    /** Whether this person renders with the slim (Alex) arm model. Synced; used by the renderer. */
    public boolean isSlim() {
        return this.entityData.get(DATA_SLIM);
    }

    /**
     * Client-only: forces a glowing outline regardless of any real (synced) glow. Driven each tick
     * by the debug wand's held-selection highlight ({@code mod.client.DebugWandGlow}). Never set
     * server-side, so never persisted or synced.
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
     * Required by {@link Avatar}. Unused for rendering — the visible skin comes from
     * {@link #getSkinTexture()} via the renderer, not from this profile.
     */
    @Override
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
