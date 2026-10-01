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
import dev.luizloyola.anima.core.social.speech.Choosers;
import dev.luizloyola.autarkia.core.person.speech.PersonActs;
import dev.luizloyola.autarkia.core.person.speech.PersonChooser;
import dev.luizloyola.autarkia.core.tree.ChopForLogs;
import dev.luizloyola.autarkia.core.board.Stock;
import dev.luizloyola.autarkia.core.board.WorkDoings;
import java.util.List;
import java.util.Objects;
import dev.luizloyola.autarkia.core.tree.Pois;
import dev.luizloyola.autarkia.core.tree.TreeFelling;
import dev.luizloyola.autarkia.core.tree.TreeRule;
import dev.luizloyola.autarkia.core.tree.WaterRule;
import dev.luizloyola.autarkia.core.patch.Landmarks;
import dev.luizloyola.autarkia.core.patch.NestRule;
import dev.luizloyola.autarkia.core.patch.StoneRule;
import dev.luizloyola.autarkia.compat.sense.LandmarkBlocks;
import net.minecraft.core.particles.ParticleTypes;
import dev.luizloyola.anima.mod.nav.PathfinderService;
import dev.luizloyola.autarkia.mod.person.PersonAppearance;
import dev.luizloyola.autarkia.mod.person.PersonDirectory;
import dev.luizloyola.autarkia.mod.person.PersonPortraits;
import dev.luizloyola.anima.mod.net.DebugGlowSync;
import net.fabricmc.api.ModInitializer;
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
        // The person's own words — asking a name, giving it, small talk. A static field
        // initializer only runs once something reads the class, so this touch is the whole
        // registration (NeedKind's idiom); Anima's registry throws if it ever ran twice.
        Objects.requireNonNull(PersonActs.ASK_IDENTITY);
        // What a settler's work is, as they tell it — the same idiom, and it has to land before any
        // Person loads: a saved history naming a doing nobody has declared yet is dropped.
        Objects.requireNonNull(WorkDoings.GATHERING);
        // The part with the personality — must come after the touch above: Picker.applicable()
        // walks SpeechActs.all() on every turn, so registration has to be finished before a
        // chooser can be handed a conversation to decide on.
        Choosers.provide(new PersonChooser());
        // A player chatting draws from the same vocabulary, read off the player.
        dev.luizloyola.anima.mod.social.PlayerTopics.provide(
                new dev.luizloyola.autarkia.mod.person.PlayerSmallTalk());
        // Settlers craft: the vanilla recipe book becomes the library's RecipeSource. Anima
        // ships the mechanism unregistered — this call is the consumer saying recipes exist.
        dev.luizloyola.anima.mod.craft.VanillaRecipeSource.install();
        // Layer 3's shared half: one board per party, ticked here rather than by anybody's body.
        PartyBoards.init();
        dev.luizloyola.anima.mod.brain.BeingViewer.init();
        KnowledgeViewer.init();
        // The tree-split survey — needs the cell overlay channel initialized above.
        dev.luizloyola.autarkia.mod.debug.TreeSplitViewer.init();
        // The fellers' own reading of the ground beside their trees, while they stand and look.
        dev.luizloyola.autarkia.mod.debug.FellViewer.init();
        // Layer 3's own: a party's clearing project drawn over the ground it covers.
        dev.luizloyola.autarkia.mod.debug.BoardViewer.init();
        // Teach Anima who Persons are. It asks only for the private tier (the name); the public
        // tier stays ours to sync. Providers chain, so a future pets mod answers for its own ids
        // beside this one.
        AgentDirectory.provide(PersonDirectory::get);
        // And what one of them looks like, for the face beside a spoken line. Deleting this line is
        // the whole disconnect: with no provider the choke point renders text-only.
        PersonPortraits.install();
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
        // this world, not about having a mind, so it belongs here rather than in the library. The
        // axe is asked for tree by tree: a wooden one wears out before a 64-log trip ends.
        Producers.register(Stock.LOGS, Stock.LOGS::matches, Stock.gatheringKit(Stock.LOGS),
                ChopForLogs::new);
        // What a walk may lay is Anima's tag; which of it a settler keeps is ours to spell.
        Stock.layableBy(dev.luizloyola.anima.mod.nav.Laying::layable);
        // And where no walk may lay or cut: a party's HOME and the places it keeps.
        dev.luizloyola.anima.mod.nav.WorkFence.rule(
                dev.luizloyola.autarkia.mod.direction.SettledGround::around);
        // And where ready food comes from before anybody farms: a berry or melon patch. The act is
        // declared here, not on first use, because the node table names it as it loads.
        dev.luizloyola.autarkia.core.patch.Forage.ACT.key();
        // Under both specs: berries are ready, so a hungry body forages for a meal as well as for
        // the party's stores.
        Producers.register(dev.luizloyola.anima.core.brain.task.ReadyFood.SPEC,
                dev.luizloyola.autarkia.core.patch.Forage::yields,
                dev.luizloyola.autarkia.core.patch.Forage::new);
        Producers.register(dev.luizloyola.anima.core.brain.task.Food.SPEC,
                dev.luizloyola.autarkia.core.patch.Forage::yields,
                dev.luizloyola.autarkia.core.patch.Forage::new);
        // Stone a furnace is made of, from where it shows (decision 19); the tag only the mod reads.
        Stock.furnaceStoneBy(dev.luizloyola.autarkia.compat.inv.ItemTagged::stoneCrafting);
        Stock.dirtBy(dev.luizloyola.autarkia.compat.inv.ItemTagged::dirt);
        dev.luizloyola.autarkia.core.patch.MineStone.ACT.key();
        Producers.register(Stock.FURNACE_STONE, Stock.FURNACE_STONE::matches,
                dev.luizloyola.autarkia.core.patch.MineStone::new);
        // And by hunting, under food alone: raw meat is not a meal, so the party's need for food is
        // what sends a Person hunting — or starving (directions spec, decisions 16 and 17).
        dev.luizloyola.autarkia.core.person.Hunting.ACT.key();
        Producers.register(dev.luizloyola.anima.core.brain.task.Food.SPEC,
                dev.luizloyola.anima.core.brain.sense.Yields::dropped,
                Stock.gatheringKit(dev.luizloyola.anima.core.brain.task.Food.SPEC),
                dev.luizloyola.autarkia.core.person.Hunting::create);
        // Picking a bush is Autarkia's to know; Anima only lends the empty hand that does it.
        dev.luizloyola.anima.mod.brain.BlockUses.register(
                dev.luizloyola.autarkia.compat.forage.BerryPicking::pick);
        // And how Autarkia's own tasks write themselves down.
        dev.luizloyola.autarkia.mod.brain.AutarkiaTasks.install();
        // What felling means when the things in a box are trees. The project knows only phases,
        // slices and a ledger; the kind, the looking and the felling all arrive through here.
        dev.luizloyola.autarkia.core.board.Fellings.register(TreeFelling.INSTANCE);
        // And what a saved row means when it says "fell_trees" — the other half of teaching a
        // party's board this kind of project, one level up from the Felling itself.
        dev.luizloyola.autarkia.core.board.PartyProjects.register(
                dev.luizloyola.autarkia.core.board.FellTrees.TYPE);
        // The second kind: go and get this much of this. How much of it one member takes on a trip
        // is its own registered policy, so a richer split can be posted later without touching
        // either the project or the store.
        dev.luizloyola.autarkia.core.board.PartyProjects.register(
                dev.luizloyola.autarkia.core.board.Gather.TYPE);
        dev.luizloyola.autarkia.core.board.Splits.register(
                dev.luizloyola.autarkia.core.board.CarrySplit.INSTANCE);
        // And that a party's work board is checked at boot like every other store: a project
        // outlives every worker who touches it, so a swallowed load would send a settlement to
        // re-walk ground it had already surveyed.
        StoreGuard.guard("party boards", PartyBoardData.ID, PartyBoardData::get);
        // Layer 4: what each line of Directions means, then the tree, the beat and the gate's
        // answers. The lines first — loading a table checks every Direction names one.
        dev.luizloyola.autarkia.core.direction.Lines.register(
                dev.luizloyola.autarkia.core.direction.AreaLine.INSTANCE);
        dev.luizloyola.autarkia.core.direction.Lines.register(
                dev.luizloyola.autarkia.core.direction.WoodLine.INSTANCE);
        dev.luizloyola.autarkia.core.direction.Lines.register(
                dev.luizloyola.autarkia.core.direction.BaseLine.INSTANCE);
        dev.luizloyola.autarkia.core.direction.Lines.register(
                dev.luizloyola.autarkia.core.direction.StorageLine.INSTANCE);
        dev.luizloyola.autarkia.core.direction.Lines.register(
                dev.luizloyola.autarkia.core.direction.FoodLine.INSTANCE);
        dev.luizloyola.autarkia.core.direction.Lines.register(
                dev.luizloyola.autarkia.core.direction.CharcoalLine.INSTANCE);
        // The line a party with no HOME works on: a search for one, which reads the world through
        // the mod's looks.
        dev.luizloyola.autarkia.core.direction.Lines.register(
                dev.luizloyola.autarkia.core.direction.HomeLine.INSTANCE);
        dev.luizloyola.autarkia.core.board.PartyProjects.register(
                dev.luizloyola.autarkia.core.board.Explore.TYPE);
        dev.luizloyola.autarkia.mod.direction.HomeLooks.init();
        // What the base and storage lines post: stations put down one at a time, as a party's job.
        dev.luizloyola.autarkia.core.board.PartyProjects.register(
                dev.luizloyola.autarkia.core.board.SetUp.TYPE);
        // And somebody comes back to a furnace when what it was given should be done.
        dev.luizloyola.autarkia.core.board.PartyProjects.register(
                dev.luizloyola.autarkia.core.board.Tend.TYPE);
        dev.luizloyola.autarkia.core.board.PartyProjects.register(
                dev.luizloyola.autarkia.core.board.Fire.TYPE);
        // Levelling ground to a plan read off the natural ground (/autarkia flatten).
        dev.luizloyola.autarkia.core.board.PartyProjects.register(
                dev.luizloyola.autarkia.core.board.Flatten.TYPE);
        dev.luizloyola.autarkia.core.board.PartyProjects.register(
                dev.luizloyola.autarkia.core.board.ClearPlants.TYPE);
        dev.luizloyola.autarkia.mod.board.Tending.init();
        dev.luizloyola.autarkia.mod.direction.Directions.init();
        dev.luizloyola.autarkia.mod.bp.Blueprints.init();
        dev.luizloyola.autarkia.mod.bp.Captures.init();
        dev.luizloyola.autarkia.mod.bp.SlowPlacements.init();
        dev.luizloyola.autarkia.mod.bp.SectionViews.init();
        dev.luizloyola.autarkia.mod.debug.HomeChoiceViewer.init();
        dev.luizloyola.autarkia.mod.debug.HouseSiteViewer.init();
        dev.luizloyola.autarkia.mod.debug.HomeAreaViewer.init();
        dev.luizloyola.autarkia.mod.debug.FlattenPlanViewer.init();
        StoreGuard.guard("directions", dev.luizloyola.autarkia.mod.direction.DirectionsData.ID,
                dev.luizloyola.autarkia.mod.direction.DirectionsData::get);
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
            rule.seeds().forEach(seed -> GrowthRules.register(seed, rule));
            KnowledgeViewer.particle(rule.kind(), ParticleTypes.COMPOSTER);
        }
        // And what makes a place worth living in, for the HOME search: stone and bee nests.
        Landmarks.init();
        LandmarkBlocks.register();
        dev.luizloyola.autarkia.compat.sense.PlantBlocks.register();
        GrowthRules.register(Landmarks.STONE, StoneRule.INSTANCE);
        GrowthRules.register(Landmarks.BEE_NEST, NestRule.INSTANCE);
        KnowledgeViewer.particle(Landmarks.STONE_POI, ParticleTypes.CRIT);
        KnowledgeViewer.particle(Landmarks.BEES, ParticleTypes.WAX_ON);
        // No right-click handler here any more: an empty-handed click is Anima's targeted hail
        // since social rung 7, and the inventory it used to open moved to `/anima inv see`
        // (decision: Luiz, 2026-08-04). What is left for us is Person.showInventory, the screen
        // that command asks for.
        LOGGER.info("Autarkia {} initialized on Minecraft {}", VERSION, MINECRAFT);
    }
}
