package dev.luizloyola.autarkia.mod.inv;

import dev.luizloyola.autarkia.mod.AutarkiaMod;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.flag.FeatureFlags;
import net.minecraft.world.inventory.MenuType;

/**
 * Registers the mod's {@link MenuType}s. The {@code MenuType} constructor is vanilla, widened by
 * Fabric's transitive access-wideners, so no extra Fabric API module is needed.
 */
public final class ModMenus {
    private ModMenus() {}

    /**
     * The "open a Person's inventory" menu. The supplier is the <em>client</em> factory (dummy
     * container); the server builds it with a live {@link PersonContainer} when a player opens it.
     */
    public static final MenuType<PersonInventoryMenu> PERSON_INVENTORY =
            new MenuType<>(PersonInventoryMenu::new, FeatureFlags.VANILLA_SET);

    public static void init() {
        Registry.register(BuiltInRegistries.MENU,
                Identifier.fromNamespaceAndPath(AutarkiaMod.MOD_ID, "person_inventory"),
                PERSON_INVENTORY);
    }
}
