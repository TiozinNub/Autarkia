package dev.luizloyola.autarkia.mod.entity;

import net.fabricmc.fabric.api.object.builder.v1.entity.FabricDefaultAttributeRegistry;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;

/** Registers Autarkia's entity types and their attributes. Call {@link #init()} from mod init. */
public final class ModEntities {
    private ModEntities() {}

    // Fully summonable (canSummon() == true): a summoned Person has no identity, so Person.tick()
    // mints a registered one (generated name + appearance) on its first server tick. /autarkia
    // person spawn only does it up front, to name the Person and place it deliberately.
    public static final EntityType<Person> PERSON = register("person",
            EntityType.Builder.of(Person::create, MobCategory.MISC)
                    .sized(0.6F, 1.8F)
                    .eyeHeight(1.62F)
                    .clientTrackingRange(32)
                    .updateInterval(2));

    private static EntityType<Person> register(String name, EntityType.Builder<Person> builder) {
        Identifier id = Identifier.fromNamespaceAndPath("autarkia", name);
        ResourceKey<EntityType<?>> key = ResourceKey.create(Registries.ENTITY_TYPE, id);
        return Registry.register(BuiltInRegistries.ENTITY_TYPE, id, builder.build(key));
    }

    /** Triggers registration (via class load) and registers default attributes. */
    public static void init() {
        FabricDefaultAttributeRegistry.register(PERSON, Person.createAttributes());
    }
}
