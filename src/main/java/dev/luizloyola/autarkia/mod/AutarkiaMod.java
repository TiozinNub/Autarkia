package dev.luizloyola.autarkia.mod;

import dev.luizloyola.autarkia.mod.command.AutarkiaCommands;
import dev.luizloyola.autarkia.mod.entity.ModEntities;
import dev.luizloyola.autarkia.mod.item.ModComponents;
import dev.luizloyola.autarkia.mod.item.ModItems;
import net.fabricmc.api.ModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class AutarkiaMod implements ModInitializer {
    public static final String MOD_ID = "autarkia";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);
    public static final String VERSION = /*$ mod_version*/ "0.1.0";
    public static final String MINECRAFT = /*$ minecraft*/ "26.1.2";

    @Override
    public void onInitialize() {
        ModEntities.init();
        ModComponents.init();
        ModItems.init();
        AutarkiaCommands.register();
        LOGGER.info("Autarkia {} initialized on Minecraft {}", VERSION, MINECRAFT);
    }
}
