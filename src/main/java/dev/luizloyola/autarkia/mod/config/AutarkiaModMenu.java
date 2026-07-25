package dev.luizloyola.autarkia.mod.config;

import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;
import net.fabricmc.loader.api.FabricLoader;

/**
 * Puts a Config button next to Autarkia in Mod Menu's list, opening {@link ClothConfigScreen}.
 *
 * <p>Both halves are optional and independently so: Mod Menu is the only thing that ever loads
 * this class (through the {@code modmenu} entrypoint), and Cloth Config's absence is caught by the
 * {@link FabricLoader#isModLoaded} check below, so this class names no
 * {@code me.shedaniel} type itself.
 *
 * <p>The id checked is the legacy {@code cloth-config2}: the modern Fabric jar still declares
 * {@code provides: ["cloth-config2"]}, and Sinytra Connector's default alias maps NeoForge's
 * {@code cloth_config} onto it, so that spelling is the one that resolves on both loaders.
 *
 * <p>Mod Menu runs only on Fabric and Quilt clients; on a dedicated server, and under Connector,
 * {@code config/autarkia.json} and {@code /autarkia config} are the whole interface.
 */
public final class AutarkiaModMenu implements ModMenuApi {

    private static final String CLOTH_CONFIG = "cloth-config2";

    @Override
    public ConfigScreenFactory<?> getModConfigScreenFactory() {
        if (!FabricLoader.getInstance().isModLoaded(CLOTH_CONFIG)) {
            return parent -> null; // Mod Menu's own default: no Config button
        }
        return ClothConfigScreen::create;
    }
}
