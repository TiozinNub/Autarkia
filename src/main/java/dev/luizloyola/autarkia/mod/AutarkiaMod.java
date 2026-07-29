package dev.luizloyola.autarkia.mod;

import dev.luizloyola.anima.mod.config.ConfigFile;
import dev.luizloyola.autarkia.core.config.AutarkiaConfig;
import dev.luizloyola.autarkia.mod.command.AutarkiaCommands;
import dev.luizloyola.autarkia.mod.entity.ModEntities;
import dev.luizloyola.autarkia.mod.entity.Person;
import dev.luizloyola.autarkia.mod.inv.ModMenus;
import dev.luizloyola.anima.mod.item.AnimaItems;
import dev.luizloyola.anima.mod.brain.Claims;
import dev.luizloyola.anima.mod.brain.DamageMarks;
import dev.luizloyola.anima.mod.brain.KnowledgeViewer;
import dev.luizloyola.anima.mod.debug.DebugView;
import dev.luizloyola.anima.mod.log.Journals;
import dev.luizloyola.autarkia.core.tree.ChopKnownTree;
import dev.luizloyola.anima.core.brain.knowledge.BlockKind;
import dev.luizloyola.anima.core.brain.knowledge.GrowthRules;
import dev.luizloyola.anima.core.brain.task.Producers;
import dev.luizloyola.anima.mod.identity.AgentDirectory;
import dev.luizloyola.autarkia.core.board.Stock;
import dev.luizloyola.autarkia.core.tree.Pois;
import dev.luizloyola.autarkia.core.tree.TreeRule;
import dev.luizloyola.autarkia.core.tree.WaterRule;
import net.minecraft.core.particles.ParticleTypes;
import dev.luizloyola.anima.mod.nav.PathfinderService;
import dev.luizloyola.autarkia.mod.person.PersonDirectory;
import dev.luizloyola.anima.mod.net.DebugGlowSync;
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

    /**
     * {@code config/autarkia.json} — today, what a Person is like. Anima keeps the schema
     * of a mind; the values for this body are ours.
     */
    public static final ConfigFile CONFIG = new ConfigFile(AutarkiaConfig.store());

    @Override
    public void onInitialize() {
        // Load before anything can read a Person's aspects. Anima's own file (the limits, the
        // journal, the flee weights) is loaded by its initializer; this one holds the species.
        for (String problem : CONFIG.reload()) {
            LOGGER.warn("autarkia.json: {}", problem);
        }
        ModEntities.init();
        AnimaItems.init();
        ModMenus.init();
        AutarkiaCommands.register(CONFIG);
        DebugGlowSync.install();
        dev.luizloyola.anima.mod.net.ContactsSync.install();
        DebugView.init();
        PathfinderService.init();
        Journals.init();
        Claims.init();
        DamageMarks.init();
        dev.luizloyola.anima.mod.brain.PlaceMarks.init();
        dev.luizloyola.anima.mod.brain.BeingViewer.init();
        dev.luizloyola.anima.mod.brain.BeingVoices.init();
        KnowledgeViewer.init();
        // Teach Anima who Persons are. It asks only for the private tier (the name); the public
        // tier stays ours to sync. Providers chain, so a future pets mod answers for its own ids
        // beside this one.
        AgentDirectory.provide(PersonDirectory::get);
        // Where logs come from is a fact about this world, not about having a mind: Anima knows how
        // to WANT an item and pick one up, so the producer is registered here, not in the library.
        Producers.register(Stock.LOGS, ChopKnownTree::new);
        // Declare what a settler finds worth remembering, and what grows into it. Anima owns the
        // crescent sampler, the region flood and the merge rule; it has no idea what a tree is.
        Pois.init();
        GrowthRules.register(BlockKind.LOG, TreeRule.INSTANCE);
        GrowthRules.register(BlockKind.LEAVES, TreeRule.INSTANCE);
        GrowthRules.register(BlockKind.WATER, WaterRule.INSTANCE);
        KnowledgeViewer.particle(Pois.TREE, ParticleTypes.HAPPY_VILLAGER);
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
