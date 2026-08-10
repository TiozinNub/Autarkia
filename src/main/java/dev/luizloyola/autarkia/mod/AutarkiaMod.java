package dev.luizloyola.autarkia.mod;

import dev.luizloyola.anima.mod.brain.DangerFile;
import dev.luizloyola.anima.mod.config.ConfigFile;
import dev.luizloyola.autarkia.core.person.PersonDanger;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import dev.luizloyola.autarkia.core.config.AutarkiaConfig;
import dev.luizloyola.autarkia.mod.board.PartyBoardData;
import dev.luizloyola.autarkia.mod.board.PartyBoards;
import dev.luizloyola.autarkia.mod.command.AutarkiaCommands;
import dev.luizloyola.autarkia.mod.entity.ModEntities;
import dev.luizloyola.autarkia.mod.entity.Person;
import dev.luizloyola.autarkia.mod.inv.ModMenus;
import dev.luizloyola.anima.mod.item.AnimaItems;
import dev.luizloyola.anima.mod.item.WandActions;
import dev.luizloyola.autarkia.mod.item.ChopWandAction;
import dev.luizloyola.anima.mod.brain.Claims;
import dev.luizloyola.anima.mod.brain.DamageMarks;
import dev.luizloyola.anima.mod.brain.KnowledgeViewer;
import dev.luizloyola.anima.mod.debug.DebugView;
import dev.luizloyola.anima.mod.log.Journals;
import dev.luizloyola.anima.core.brain.knowledge.BlockKind;
import dev.luizloyola.anima.core.brain.knowledge.GrowthRules;
import dev.luizloyola.anima.mod.identity.AgentDirectory;
import dev.luizloyola.anima.mod.identity.AgentRecords;
import dev.luizloyola.anima.mod.store.StoreGuard;
import dev.luizloyola.anima.core.brain.task.Producers;
import dev.luizloyola.autarkia.compat.sense.PatchBlocks;
import dev.luizloyola.autarkia.core.patch.PatchRule;
import dev.luizloyola.autarkia.core.patch.Patches;
import dev.luizloyola.autarkia.core.tree.ChopForLogs;
import dev.luizloyola.autarkia.core.board.Stock;
import java.util.List;
import dev.luizloyola.autarkia.core.tree.Pois;
import dev.luizloyola.autarkia.core.tree.TreeClearing;
import dev.luizloyola.autarkia.core.tree.TreeRule;
import dev.luizloyola.autarkia.core.tree.WaterRule;
import net.minecraft.core.particles.ParticleTypes;
import dev.luizloyola.anima.mod.nav.PathfinderService;
import dev.luizloyola.autarkia.mod.person.PersonAppearance;
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
     * {@code config/autarkia.toml} — today, what a Person is like. Anima keeps the schema
     * of a mind; the values describing this body are ours, so they live in our file behind our
     * command.
     */
    public static final ConfigFile CONFIG = new ConfigFile(AutarkiaConfig.store());

    @Override
    public void onInitialize() {
        // Load before anything can read a Person's aspects. Anima's own file (the limits, the
        // journal, the flee weights) is loaded by its initializer; this one holds the species.
        for (String problem : CONFIG.reload()) {
            LOGGER.warn("autarkia.toml: {}", problem);
        }
        // The weights cannot be generated here: modded and datapack entity types only exist
        // once the registries freeze, so this waits for a server to start.
        ServerLifecycleEvents.SERVER_STARTING.register(server ->
                new DangerFile(MOD_ID, PersonDanger.STORE).generate());
        // Say what the wardrobe came to, once, at INFO: a catalog that failed to load or an art
        // folder that did not ship otherwise shows up only as settlers who quietly look wrong.
        PersonAppearance.describe().forEach(LOGGER::info);
        ModEntities.init();
        AnimaItems.init();
        ModMenus.init();
        AutarkiaCommands.register(CONFIG);
        DebugGlowSync.install();
        dev.luizloyola.anima.mod.net.ContactsSync.install();
        DebugView.init();
        dev.luizloyola.anima.mod.debug.CellOverlays.init();
        PathfinderService.init();
        Journals.init();
        Claims.init();
        // Layer 3's shared half: one board per party, ticked here rather than by anybody's body.
        PartyBoards.init();
        DamageMarks.init();
        dev.luizloyola.anima.mod.brain.PlaceMarks.init();
        dev.luizloyola.anima.mod.brain.BeingViewer.init();
        dev.luizloyola.anima.mod.brain.BeingVoices.init();
        KnowledgeViewer.init();
        // The tree-split survey — needs the cell overlay channel initialized above.
        dev.luizloyola.autarkia.mod.debug.TreeSplitViewer.init();
        // The same trees, drawn with their chop dance cards.
        dev.luizloyola.autarkia.mod.debug.TreeChopPlanViewer.init();
        // Layer 3's own: a party's clearing project drawn over the ground it covers.
        dev.luizloyola.autarkia.mod.debug.BoardViewer.init();
        // Teach Anima who Persons are. It asks only for the private tier (the name); the public
        // tier stays ours to sync. Providers chain, so a future pets mod answers for its own ids
        // beside this one.
        AgentDirectory.provide(PersonDirectory::get);
        // What to do when one of those Persons is let go. Registered here rather than inside
        // whatever command does the letting go: the store's author is the only one who reliably
        // remembers it exists. `survivesDeath = true` because identity outlives the body — only an
        // ERASURE (a Person unmade by command, who never died) takes this row.
        AgentRecords.register("identity", true,
                (server, who) -> PersonDirectory.get(server).purge(who));
        // And that the directory is checked at boot for a load vanilla swallowed. Anima installs
        // the guard itself and registers its own three stores; this is the consumer's.
        StoreGuard.guard("identity", PersonDirectory.ID, PersonDirectory::get);
        // Teach the brain where logs come from. That wood comes of felling a tree is a fact about
        // this world, not about having a mind, so it belongs here rather than in the library.
        // Since the seventh choreography (2026-08-02), obtain orders the dance card itself.
        Producers.register(Stock.LOGS, ChopForLogs::new);
        // And how the dance card writes itself down, so a chop survives a reload mid-tree.
        dev.luizloyola.autarkia.mod.brain.AutarkiaTasks.install();
        // What "clear this area" means when the things in it are trees. The project knows only
        // phases, slices and a ledger; the kind, the looking and the felling all arrive through
        // here, which leaves room for boulders later.
        dev.luizloyola.autarkia.core.board.Clearings.register(TreeClearing.INSTANCE);
        // And that a party's work board is checked at boot like every other store: a project
        // outlives every worker who touches it, so a swallowed load would send a settlement to
        // re-walk ground it had already surveyed.
        StoreGuard.guard("party boards", PartyBoardData.ID, PartyBoardData::get);
        // Teach the debug wand what a block MEANS to a settler — Anima's wand can point at
        // anything and knows what none of it is. Unclaimed clicks still fall back to walking
        // there.
        WandActions.register(new ChopWandAction());
        // Declare what a settler finds worth remembering, and what grows into it. Anima owns the
        // crescent sampler, the region flood and the merge rule; it has no idea what a tree is.
        Pois.init();
        GrowthRules.register(BlockKind.LOG, TreeRule.INSTANCE);
        GrowthRules.register(BlockKind.LEAVES, TreeRule.INSTANCE);
        GrowthRules.register(BlockKind.WATER, WaterRule.INSTANCE);
        KnowledgeViewer.particle(Pois.TREE, ParticleTypes.HAPPY_VILLAGER);
        // And what grows in scattered clumps rather than in masses. Two registrations each, not
        // one: PatchBlocks teaches Anima to tell a pumpkin from a stone, PatchRule says what to
        // believe about it. Declaring a block kind is not declaring it worth remembering.
        Patches.init();
        PatchBlocks.register();
        for (PatchRule rule : PatchRule.ALL) {
            GrowthRules.register(rule.seed(), rule);
            KnowledgeViewer.particle(rule.kind(), ParticleTypes.COMPOSTER);
        }
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
