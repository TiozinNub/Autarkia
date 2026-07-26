package dev.luizloyola.autarkia.mod.config;

import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;
import net.fabricmc.loader.api.FabricLoader;

/**
 * Puts a Config button next to Autarkia in Mod Menu's list, opening {@link YaclConfigScreen}.
 *
 * <p>Both halves are optional, independently. Mod Menu is the only thing that loads this class (the
 * {@code modmenu} entrypoint); YACL's absence is caught by the {@link FabricLoader#isModLoaded}
 * check below, returning Mod Menu's own "no screen" answer — hence no {@code dev.isxander} type is
 * named here.
 *
 * <p>The id is YACL's own and the same on Fabric and NeoForge, so nothing depends on Sinytra
 * Connector's mod-alias table — unlike Cloth Config, which this replaced and which crosses loaders
 * only under its legacy {@code cloth-config2} id.
 *
 * <p>Mod Menu runs on Fabric and Quilt clients only, and Autarkia's Fabric-through-Connector jar
 * cannot hook NeoForge's config-screen extension point: on a dedicated server and for NeoForge
 * users, {@code config/autarkia.json} and {@code /autarkia config} are the whole interface.
 */
public final class AutarkiaModMenu implements ModMenuApi {

    private static final String YACL = "yet_another_config_lib_v3";

    @Override
    public ConfigScreenFactory<?> getModConfigScreenFactory() {
        if (!FabricLoader.getInstance().isModLoaded(YACL)) {
            return parent -> null; // Mod Menu's own default: no Config button
        }
        return YaclConfigScreen::create;
    }
}
