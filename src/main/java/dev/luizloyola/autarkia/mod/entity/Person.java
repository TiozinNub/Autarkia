package dev.luizloyola.autarkia.mod.entity;

import dev.luizloyola.autarkia.core.person.PersonId;
import dev.luizloyola.autarkia.mod.person.PersonDirectory;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Avatar;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.decoration.Mannequin;
import net.minecraft.world.item.component.ResolvableProfile;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
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

    private static final String TAG_PERSON_ID = "PersonId";

    /**
     * This entity's link to its full identity in the world-scoped {@link PersonDirectory} — a
     * reference only, so the name and the rest outlive the entity. Server-side and not synced: the
     * client needs only the entity to ask who this is. {@code null} until first assigned (see
     * {@link #tick()}).
     */
    private @Nullable PersonId personId;

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
        return LivingEntity.createLivingAttributes();
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        super.defineSynchedData(builder);
        builder.define(DATA_SKIN, DEFAULT_SKIN.toString());
    }

    @Override
    public void tick() {
        super.tick();
        // Assign a persistent identity on first server tick. Lazy (not in a constructor) because it
        // needs the running server; Avatar is not a Mob, so there is no finalizeSpawn hook.
        if (this.personId == null && this.level() instanceof ServerLevel serverLevel) {
            this.personId = PersonDirectory.get(serverLevel.getServer()).createPerson().id();
        }
    }

    /** This person's directory handle, or {@code null} before it has been assigned (client, or the
     *  spawn tick has not run yet). Resolve names/identity via {@link PersonDirectory}. */
    public @Nullable PersonId getPersonId() {
        return this.personId;
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
        this.personId = input.read(TAG_PERSON_ID, UUIDUtil.CODEC).map(PersonId::of).orElse(null);
    }

    public Identifier getSkinTexture() {
        return Identifier.parse(this.entityData.get(DATA_SKIN));
    }

    public void setSkinTexture(Identifier id) {
        this.entityData.set(DATA_SKIN, id.toString());
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
