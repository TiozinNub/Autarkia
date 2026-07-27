package dev.luizloyola.autarkia.mod;

import dev.luizloyola.autarkia.mod.command.AutarkiaCommands;
import dev.luizloyola.autarkia.mod.config.ConfigFile;
import dev.luizloyola.autarkia.mod.entity.ModEntities;
import dev.luizloyola.autarkia.mod.entity.Person;
import dev.luizloyola.autarkia.mod.inv.ModMenus;
import dev.luizloyola.autarkia.mod.item.ModItems;
import dev.luizloyola.autarkia.mod.brain.Claims;
import dev.luizloyola.autarkia.mod.brain.DamageMarks;
import dev.luizloyola.autarkia.mod.brain.KnowledgeViewer;
import dev.luizloyola.autarkia.mod.debug.DebugView;
import dev.luizloyola.autarkia.mod.log.Journals;
import dev.luizloyola.autarkia.mod.nav.PathfinderService;
import dev.luizloyola.autarkia.mod.net.DebugGlowSync;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class AutarkiaMod implements ModInitializer {
    public static final String MOD_ID = "autarkia";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);
    public static final String VERSION = /*$ mod_version*/ "0.1.0";
    public static final String MINECRAFT = /*$ minecraft*/ "26.1.2";

    @Override
    public void onInitialize() {
        // First: everything below may read a tuning knob, and the simulation certainly does.
        ConfigFile.reload();
        ModEntities.init();
        ModItems.init();
        ModMenus.init();
        AutarkiaCommands.register();
        DebugGlowSync.install();
        dev.luizloyola.autarkia.mod.net.ContactsSync.install();
        DebugView.init();
        PathfinderService.init();
        Journals.init();
        Claims.init();
        DamageMarks.init();
        dev.luizloyola.autarkia.mod.brain.PlaceMarks.init();
        dev.luizloyola.autarkia.mod.brain.BeingViewer.init();
        dev.luizloyola.autarkia.mod.brain.BeingVoices.init();
        KnowledgeViewer.init();
        registerInteraction();
        LOGGER.info("Autarkia {} initialized on Minecraft {}", VERSION, MINECRAFT);
    }

    /**
     * Right-click a Person with an empty main hand to open its inventory. A Fabric
     * {@code UseEntityCallback} (fires before the entity's own interact and before any held item's
     * use) rather than a vanilla {@code Entity#interact} override, whose signature drifts across MC
     * versions — this keeps the entity class free of version-specific code. Returning {@code PASS}
     * for any other case lets vanilla proceed, so e.g. the debug wand still selects the Person.
     */
    private static void registerInteraction() {
        UseEntityCallback.EVENT.register((player, world, hand, entity, hitResult) -> {
            if (entity instanceof Person person
                    && hand == InteractionHand.MAIN_HAND
                    && player.getItemInHand(hand).isEmpty()) {
                return person.openInventory(player);
            }
            return InteractionResult.PASS;
        });
    }
}
