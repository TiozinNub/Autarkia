package dev.luizloyola.autarkia.mod.entity;

import dev.luizloyola.autarkia.core.person.Appearance;
import dev.luizloyola.autarkia.core.person.Gender;
import dev.luizloyola.autarkia.core.person.ModelType;
import dev.luizloyola.autarkia.core.person.PersonId;
import dev.luizloyola.autarkia.core.person.PersonIdentity;
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
import net.minecraft.world.entity.Avatar;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.decoration.Mannequin;
import net.minecraft.world.item.component.ResolvableProfile;
import net.minecraft.world.level.Level;
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

    /**
     * Send this person walking toward {@code target} (world coordinates). The {@link Navigator}
     * steers from here on. Cancels the debug jump-sprinter first — the two both own the forward
     * input, so only one may drive at a time.
     */
    public void navigateTo(Vec3 target) {
        if (this.debugRunForward) {
            this.debugRunForward = false;
            setSprinting(false);
        }
        this.navigator.moveTo(target);
    }

    /**
     * Movement control (the primitive the {@link Navigator} drives): face {@code heading} and apply
     * a single tick of forward walking input. Vanilla physics turns that into motion in
     * {@link #travel} — see {@link #serverAiStep()}. Must be called every tick to keep moving; the
     * input is consumed and reset each tick.
     */
    public void driveForward(float heading) {
        face(heading);
        // getAttributeValue includes any sprint modifier; for a plain (non-sprinting) walk this is
        // MOVEMENT_SPEED (0.1), and the 0.98 damping keeps it exact versus a walking player.
        setSpeed((float) getAttributeValue(Attributes.MOVEMENT_SPEED));
        this.zza = PLAYER_INPUT_DAMPING; // forward along the current yRot; no strafe
    }

    /** Movement control: hold still this tick — no forward input, no jump. */
    public void stopMoving() {
        this.zza = 0.0F;
        setJumping(false);
    }

    /** Snap body and head to face {@code heading} (degrees), so "forward" renders correctly. */
    private void face(float heading) {
        setYRot(heading);
        setYHeadRot(heading);
        this.yBodyRot = heading;
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
    }

    @Override
    protected void readAdditionalSaveData(ValueInput input) {
        super.readAdditionalSaveData(input);
        input.read(TAG_PERSON_ID, UUIDUtil.CODEC).map(PersonId::of).ifPresent(this::setPersonId);
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
}
