package dev.luizloyola.autarkia.mod.command;

import com.mojang.brigadier.arguments.FloatArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import dev.luizloyola.autarkia.compat.inv.ItemStacks;
import dev.luizloyola.autarkia.core.brain.knowledge.PersonKnowledge;
import dev.luizloyola.autarkia.core.brain.knowledge.PoiKind;
import dev.luizloyola.autarkia.core.brain.knowledge.PoiMemory;
import dev.luizloyola.autarkia.core.brain.sense.Peer;
import dev.luizloyola.autarkia.core.brain.task.BreakBlock;
import dev.luizloyola.autarkia.core.brain.task.ChopNearestTree;
import dev.luizloyola.autarkia.core.brain.task.GoTo;
import dev.luizloyola.autarkia.core.brain.task.ObtainItem;
import dev.luizloyola.autarkia.core.brain.task.SatisfyHunger;
import dev.luizloyola.autarkia.core.config.AutarkiaConfig;
import dev.luizloyola.autarkia.core.config.Config;
import dev.luizloyola.autarkia.core.config.Knob;
import dev.luizloyola.autarkia.core.inv.ArmorType;
import dev.luizloyola.autarkia.core.inv.Inventory;
import dev.luizloyola.autarkia.core.inv.ItemSpec;
import dev.luizloyola.autarkia.core.log.Category;
import dev.luizloyola.autarkia.core.log.Entry;
import dev.luizloyola.autarkia.core.log.JournalService;
import dev.luizloyola.autarkia.core.person.Appearance;
import dev.luizloyola.autarkia.core.person.Needs;
import dev.luizloyola.autarkia.core.person.PersonId;
import dev.luizloyola.autarkia.core.person.PersonIdentity;
import dev.luizloyola.autarkia.mod.brain.KnowledgeViewer;
import dev.luizloyola.autarkia.mod.brain.Knowledges;
import dev.luizloyola.autarkia.mod.config.ConfigFile;
import dev.luizloyola.autarkia.mod.entity.ModEntities;
import dev.luizloyola.autarkia.mod.entity.Person;
import dev.luizloyola.autarkia.mod.log.Journals;
import dev.luizloyola.autarkia.mod.person.PersonDirectory;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.commands.arguments.coordinates.Vec3Argument;
import net.minecraft.commands.arguments.item.ItemArgument;
import net.minecraft.commands.arguments.item.ItemInput;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Developer/admin commands for inspecting Autarkia state.
 *
 * <p>{@code whois} prints a Person's identity (id + name), read straight from the server-side
 * {@link PersonDirectory}: the name is never synced to clients.
 *
 * <p>{@code nav} drives the legs directly (locomotion debug) where {@code brain} runs the same work
 * through the task machinery the arbiter feeds; {@code brain auto true | false} flips autonomy — ON
 * by default, and a manual {@code goto}/{@code eat} flips it OFF the moment it runs.
 *
 * <p>{@code log} reads the resolved Person's in-memory journal ring, all subsystems or one;
 * {@code log for <name|id>} reaches any person by directory lookup, including one whose entity is
 * unloaded (the ring is {@code PersonId}-keyed and outlives the entity). The durable per-person
 * file is separate.
 *
 * <p>{@code person spawn [<pos>] [name]} registers an identity in the {@link PersonDirectory} and
 * links it to the entity before it enters the world; a plain {@code /summon autarkia:person}
 * instead mints one on the entity's first server tick. Position mirrors {@code /summon}.
 * {@code person spawn nobrain} is that same path with autonomy off: an inert body for exercising
 * one feature at a time.
 *
 * <p>Every person-scoped subcommand resolves through {@link #resolve}: the source's pin, else the
 * nearest. {@code select} pins by name or short-id, or by what a player is looking at, unpinning
 * when they look at nobody; {@code list} enumerates the loaded Persons, since names are not unique.
 * Pins live in {@link PersonSelection} — in memory, per source, gone on restart.
 */
public final class AutarkiaCommands {
    private AutarkiaCommands() {}

    private static final double NEAREST_RADIUS = 32.0;

    /** Default number of journal lines {@code /autarkia log} prints when no count is given. */
    private static final int DEFAULT_LOG_COUNT = 30;

    /** The equipment slots a Person actually has (a player's set): both hands + the four armor pieces. */
    private static final java.util.Set<EquipmentSlot> PERSON_EQUIP_SLOTS = java.util.EnumSet.of(
            EquipmentSlot.MAINHAND, EquipmentSlot.OFFHAND,
            EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET);

    public static void register() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
                dispatcher.register(Commands.literal("autarkia")
                        // Pin the Person that this source's later commands target. "clear"/"show" are
                        // literals, so they win over a Person literally named clear/show — pin those by id.
                        .then(Commands.literal("select")
                                .executes(ctx -> selectHere(ctx.getSource()))
                                .then(Commands.literal("clear")
                                        .executes(ctx -> selectClear(ctx.getSource())))
                                .then(Commands.literal("show")
                                        .executes(ctx -> selectShow(ctx.getSource())))
                                .then(Commands.argument("person", StringArgumentType.string())
                                        .suggests(PERSON_SUGGESTIONS)
                                        .executes(ctx -> selectByToken(ctx.getSource(),
                                                StringArgumentType.getString(ctx, "person")))))
                        .then(Commands.literal("list")
                                .executes(ctx -> listPersons(ctx.getSource())))
                        .then(Commands.literal("whois")
                                .executes(ctx -> whoisNearest(ctx.getSource()))
                                .then(Commands.argument("targets", EntityArgument.entities())
                                        .executes(ctx -> whoisTargets(ctx.getSource(),
                                                EntityArgument.getEntities(ctx, "targets")))))
                        .then(Commands.literal("nav")
                                .then(Commands.literal("goto")
                                        .then(Commands.argument("pos", BlockPosArgument.blockPos())
                                                .executes(ctx -> navGoto(ctx.getSource(),
                                                        BlockPosArgument.getLoadedBlockPos(ctx, "pos")))))
                                .then(Commands.literal("stop")
                                        .executes(ctx -> navStop(ctx.getSource())))
                                .then(Commands.literal("status")
                                        .executes(ctx -> navStatus(ctx.getSource()))))
                        // nav (above) drives the legs directly — locomotion debug; brain runs
                        // tasks through the executor, the machinery the arbiter will feed.
                        .then(Commands.literal("brain")
                                .then(Commands.literal("goto")
                                        .then(Commands.argument("pos", BlockPosArgument.blockPos())
                                                .executes(ctx -> brainGoto(ctx.getSource(),
                                                        BlockPosArgument.getLoadedBlockPos(ctx, "pos")))))
                                .then(Commands.literal("eat")
                                        .executes(ctx -> brainEat(ctx.getSource())))
                                .then(Commands.literal("break")
                                        .then(Commands.argument("pos", BlockPosArgument.blockPos())
                                                .executes(ctx -> brainBreak(ctx.getSource(),
                                                        BlockPosArgument.getLoadedBlockPos(ctx, "pos")))))
                                .then(Commands.literal("chop")
                                        .executes(ctx -> brainChop(ctx.getSource())))
                                .then(Commands.literal("obtain")
                                        .then(Commands.literal("logs")
                                                .executes(ctx -> brainObtain(ctx.getSource(), 16))
                                                .then(Commands.argument("count", IntegerArgumentType.integer(1))
                                                        .executes(ctx -> brainObtain(ctx.getSource(),
                                                                IntegerArgumentType.getInteger(ctx, "count"))))))
                                .then(Commands.literal("status")
                                        .executes(ctx -> brainStatus(ctx.getSource())))
                                .then(Commands.literal("cancel")
                                        .executes(ctx -> brainCancel(ctx.getSource())))
                                // The autonomy switch — spawns start ON.
                                .then(Commands.literal("auto")
                                        .then(Commands.literal("true")
                                                .executes(ctx -> brainAuto(ctx.getSource(), true)))
                                        .then(Commands.literal("false")
                                                .executes(ctx -> brainAuto(ctx.getSource(), false)))))
                        // The per-person debug journal (see the log package). Top-level, not under a
                        // subsystem group, because one Person's log interleaves brain + pathfind + body.
                        .then(Commands.literal("log")
                                .executes(ctx -> logDump(ctx.getSource(), null, DEFAULT_LOG_COUNT))
                                .then(Commands.argument("count", IntegerArgumentType.integer(1))
                                        .executes(ctx -> logDump(ctx.getSource(), null,
                                                IntegerArgumentType.getInteger(ctx, "count"))))
                                .then(logCategory("brain", Category.BRAIN))
                                .then(logCategory("pathfind", Category.PATHFIND))
                                .then(logCategory("body", Category.BODY))
                                .then(logCategory("sense", Category.SENSE))
                                .then(logCategory("project", Category.PROJECT))
                                // "for <name|id>" reaches any person by directory lookup — including one
                                // whose entity is unloaded (the ring is PersonId-keyed and outlives the
                                // entity), which the loaded-only nearest/pinned resolve() cannot.
                                .then(Commands.literal("for")
                                        .then(Commands.argument("person", StringArgumentType.string())
                                                .suggests(ALL_PERSON_SUGGESTIONS)
                                                .executes(ctx -> logFor(ctx.getSource(),
                                                        StringArgumentType.getString(ctx, "person"),
                                                        null, DEFAULT_LOG_COUNT))
                                                .then(Commands.argument("count", IntegerArgumentType.integer(1))
                                                        .executes(ctx -> logFor(ctx.getSource(),
                                                                StringArgumentType.getString(ctx, "person"),
                                                                null, IntegerArgumentType.getInteger(ctx, "count"))))
                                                .then(logForCategory("brain", Category.BRAIN))
                                                .then(logForCategory("pathfind", Category.PATHFIND))
                                                .then(logForCategory("body", Category.BODY))
                                                .then(logForCategory("sense", Category.SENSE)))))
                        // What the resolved Person REMEMBERS (the knowledge store) — beliefs, not
                        // world state; "view" renders those beliefs as particles + discovery chat.
                        .then(Commands.literal("knowledge")
                                .executes(ctx -> knowledgeList(ctx.getSource()))
                                .then(Commands.literal("view")
                                        .then(Commands.literal("true")
                                                .executes(ctx -> knowledgeView(ctx.getSource(), true)))
                                        .then(Commands.literal("false")
                                                .executes(ctx -> knowledgeView(ctx.getSource(), false)))))
                        // The tuning knobs (config/autarkia.json). Unlike everything above, this
                        // block is world-wide, not per-Person — no selection is consulted.
                        .then(Commands.literal("config")
                                .executes(ctx -> configShow(ctx.getSource()))
                                .then(Commands.literal("show")
                                        .executes(ctx -> configShow(ctx.getSource())))
                                .then(Commands.literal("reload")
                                        .executes(ctx -> configReload(ctx.getSource())))
                                .then(Commands.literal("get")
                                        .then(Commands.argument("key", StringArgumentType.string())
                                                .suggests(KNOB_SUGGESTIONS)
                                                .executes(ctx -> configGet(ctx.getSource(),
                                                        StringArgumentType.getString(ctx, "key")))))
                                .then(Commands.literal("set")
                                        .then(Commands.argument("key", StringArgumentType.string())
                                                .suggests(KNOB_SUGGESTIONS)
                                                .then(Commands.argument("value", StringArgumentType.string())
                                                        .executes(ctx -> configSet(ctx.getSource(),
                                                                StringArgumentType.getString(ctx, "key"),
                                                                StringArgumentType.getString(ctx, "value"))))))
                                .then(Commands.literal("reset")
                                        .then(Commands.literal("all")
                                                .executes(ctx -> configResetAll(ctx.getSource())))
                                        .then(Commands.argument("key", StringArgumentType.string())
                                                .suggests(KNOB_SUGGESTIONS)
                                                .executes(ctx -> configReset(ctx.getSource(),
                                                        StringArgumentType.getString(ctx, "key"))))))
                        // The personal board (layer 3's degenerate v1): posted/claimed/cooling.
                        .then(Commands.literal("board")
                                .executes(ctx -> boardShow(ctx.getSource())))
                        // Who she can currently SEE — the peers() sense: Persons and live
                        // players, one seamless list, activity read off the visible body.
                        .then(Commands.literal("peers")
                                .executes(ctx -> peersList(ctx.getSource()))
                                .then(Commands.literal("view")
                                        .then(Commands.literal("true")
                                                .executes(ctx -> peersView(ctx.getSource(), true)))
                                        .then(Commands.literal("false")
                                                .executes(ctx -> peersView(ctx.getSource(), false)))))
                        .then(Commands.literal("inv")
                                .then(Commands.literal("list")
                                        .executes(ctx -> invList(ctx.getSource())))
                                .then(Commands.literal("clear")
                                        .executes(ctx -> invClear(ctx.getSource())))
                                .then(Commands.literal("give")
                                        .then(Commands.argument("item", ItemArgument.item(registryAccess))
                                                .executes(ctx -> invGive(ctx.getSource(),
                                                        ItemArgument.getItem(ctx, "item"), 1))
                                                .then(Commands.argument("count", IntegerArgumentType.integer(1))
                                                        .executes(ctx -> invGive(ctx.getSource(),
                                                                ItemArgument.getItem(ctx, "item"),
                                                                IntegerArgumentType.getInteger(ctx, "count"))))))
                                .then(Commands.literal("equip")
                                        .then(Commands.argument("item", ItemArgument.item(registryAccess))
                                                .executes(ctx -> invEquip(ctx.getSource(),
                                                        ItemArgument.getItem(ctx, "item"))))))
                        // "person", not "brain": these are body readouts (vitals live with the
                        // entity); the brain group above holds the decision machinery.
                        .then(Commands.literal("person")
                                // "spawn" makes an autonomous Person; "nobrain" makes one with
                                // autonomy off, for testing one feature at a time. Both take the same
                                // [<pos>] [name] leaves (see spawnLeaves) and differ only in the brain
                                // flag; "nobrain" is a literal, so quote it to use it as a name.
                                .then(spawnLeaves(Commands.literal("spawn"), true)
                                        .then(spawnLeaves(Commands.literal("nobrain"), false)))
                                // Every identity with no loaded entity loses its directory entry,
                                // knowledge and journal ring. Real deaths keep identity;
                                // this is for test-world churn.
                                .then(Commands.literal("purge")
                                        .then(Commands.literal("graveyard")
                                                .executes(ctx -> purgeGraveyard(ctx.getSource()))))
                                .then(Commands.literal("needs")
                                        .executes(ctx -> personNeeds(ctx.getSource())))
                                .then(Commands.literal("setfood")
                                        .then(Commands.argument("food",
                                                        IntegerArgumentType.integer(0, Needs.MAX_FOOD))
                                                .executes(ctx -> personSetFood(ctx.getSource(),
                                                        IntegerArgumentType.getInteger(ctx, "food"), 0.0F))
                                                .then(Commands.argument("saturation",
                                                                FloatArgumentType.floatArg(0.0F, Needs.MAX_FOOD))
                                                        .executes(ctx -> personSetFood(ctx.getSource(),
                                                                IntegerArgumentType.getInteger(ctx, "food"),
                                                                FloatArgumentType.getFloat(ctx, "saturation")))))))));
    }

    private static int navGoto(CommandSourceStack source, BlockPos pos) {
        Person person = resolve(source);
        if (person == null) return 0;
        person.navigateTo(Vec3.atBottomCenterOf(pos));
        source.sendSuccess(() -> Component.literal(person.getName().getString() + " -> "
                + pos.toShortString()).withStyle(ChatFormatting.AQUA), false);
        return 1;
    }

    private static int navStop(CommandSourceStack source) {
        Person person = resolve(source);
        if (person == null) return 0;
        person.navigator().stop();
        source.sendSuccess(() -> Component.literal(person.getName().getString() + " stopped.")
                .withStyle(ChatFormatting.AQUA), false);
        return 1;
    }

    private static int navStatus(CommandSourceStack source) {
        Person person = resolve(source);
        if (person == null) return 0;
        source.sendSuccess(() -> Component.literal(person.getName().getString() + ": "
                + person.navigator().describe()).withStyle(ChatFormatting.AQUA), false);
        return 1;
    }

    /** Runs a {@link GoTo} task on the nearest Person through the brain's executor — same walk
     *  as {@link #navGoto}, but through the task machinery, so the whole pipeline is exercised. */
    private static int brainGoto(CommandSourceStack source, BlockPos pos) {
        Person person = resolve(source);
        if (person == null) return 0;
        boolean autoDisabled = person.brain().run(new GoTo(pos.getX(), pos.getY(), pos.getZ()));
        String suffix = autoDisabledSuffix(autoDisabled);
        source.sendSuccess(() -> Component.literal(person.getName().getString() + ": "
                + person.brain().describe() + suffix).withStyle(ChatFormatting.AQUA), false);
        return 1;
    }

    /** Runs a {@link BreakBlock} on the resolved Person — the working arm's debug leaf (slice-2
     *  ladder step 1): reach-checked, vanilla break time for the held stack, real drops. */
    private static int brainBreak(CommandSourceStack source, BlockPos pos) {
        Person person = resolve(source);
        if (person == null) return 0;
        boolean autoDisabled = person.brain().run(new BreakBlock(pos.getX(), pos.getY(), pos.getZ()));
        String suffix = autoDisabledSuffix(autoDisabled);
        source.sendSuccess(() -> Component.literal(person.getName().getString() + ": "
                + person.brain().describe() + suffix).withStyle(ChatFormatting.AQUA), false);
        return 1;
    }

    /** Purges every identity with no loaded entity — dev hygiene for test-world churn. */
    private static int purgeGraveyard(CommandSourceStack source) {
        MinecraftServer server = source.getServer();
        PersonDirectory directory = PersonDirectory.get(server);
        java.util.Set<PersonId> loaded = new java.util.HashSet<>();
        for (Person person : loadedPersons(server)) {
            if (person.getPersonId() != null) {
                loaded.add(person.getPersonId());
            }
        }
        List<PersonId> dead = new ArrayList<>();
        for (PersonIdentity identity : directory.all()) {
            if (!loaded.contains(identity.id())) {
                dead.add(identity.id());
            }
        }
        var knowledge = Knowledges.of(server);
        JournalService journals = Journals.of(server);
        for (PersonId id : dead) {
            directory.purge(id);
            knowledge.remove(id);
            journals.drop(id);
        }
        source.sendSuccess(() -> Component.literal("Purged " + dead.size()
                + " unloaded identit" + (dead.size() == 1 ? "y" : "ies")
                + " (directory + knowledge + journal ring).").withStyle(ChatFormatting.GRAY), false);
        return dead.size();
    }

    /** Prints the resolved Person's personal board — the work-demand side of the brain. */
    private static int boardShow(CommandSourceStack source) {
        Person person = resolve(source);
        if (person == null) return 0;
        source.sendSuccess(() -> Component.literal(person.getName().getString() + " board: "
                + person.brain().describeBoard()).withStyle(ChatFormatting.LIGHT_PURPLE), false);
        return 1;
    }

    /**
     * Toggles live narration of the resolved Person's peer events ({@code PeerViewer}) — the
     * people-sense twin of {@code knowledge view}. From a player, the lines whisper to that
     * player; from the console they broadcast (and land in the server log). That is what
     * makes the toggle usable headlessly.
     */
    private static int peersView(CommandSourceStack source, boolean on) {
        Person person = resolve(source);
        if (person == null) return 0;
        PersonId id = person.getPersonId();
        if (id == null) {
            source.sendFailure(Component.literal("That Person isn't identified yet (still spawning)."));
            return 0;
        }
        String name = person.getName().getString();
        if (on) {
            ServerPlayer player = source.getPlayer();
            dev.luizloyola.autarkia.mod.brain.PeerViewer.watch(source.getServer(), id,
                    player == null ? null : player.getUUID());
            source.sendSuccess(() -> Component.literal("Narrating " + name
                            + "'s people sense — spotted/lost/activity lines land in "
                            + (player == null ? "everyone's chat (console toggle)." : "your chat."))
                    .withStyle(ChatFormatting.GREEN), false);
        } else {
            boolean was = dev.luizloyola.autarkia.mod.brain.PeerViewer.unwatch(source.getServer(), id);
            source.sendSuccess(() -> Component.literal(was
                            ? "Stopped narrating " + name + "'s people sense."
                            : name + "'s people sense wasn't being narrated.")
                    .withStyle(ChatFormatting.YELLOW), false);
        }
        return 1;
    }

    /** The resolved Person's live {@code peers()} reading — who she sees, and what they're visibly doing. */
    private static int peersList(CommandSourceStack source) {
        Person person = resolve(source);
        if (person == null) return 0;
        List<Peer> peers = person.brain().percepts().peers();
        String name = person.getName().getString();
        if (peers.isEmpty()) {
            source.sendSuccess(() -> Component.literal(name + " sees nobody around.")
                    .withStyle(ChatFormatting.GRAY), false);
            return 0;
        }
        source.sendSuccess(() -> Component.literal(name + " — " + peers.size() + " in sight")
                .withStyle(ChatFormatting.AQUA), false);
        for (Peer peer : peers) {
            String line = String.format(Locale.ROOT, "%s (%d, %d, %d) - %.1f blocks away, %s%s%s",
                    peer.name(), peer.pos().x(), peer.pos().y(), peer.pos().z(),
                    peer.distance(), peer.activity().name().toLowerCase(Locale.ROOT),
                    peer.sneaking() ? ", sneaking" : "",
                    peer.awareness() == Peer.Awareness.SEEN
                            ? "" : " [" + peer.awareness().name().toLowerCase(Locale.ROOT) + "]");
            source.sendSuccess(() -> Component.literal(line)
                    .withStyle(peer.awareness() == Peer.Awareness.REMEMBERED
                            ? ChatFormatting.GRAY : ChatFormatting.GREEN), false);
        }
        return peers.size();
    }

    // --- config -----------------------------------------------------------------------------
    // World-wide tuning, not per-Person: none of these resolve a selection. Every mutating path
    // installs the new value and writes the file, so a reload restores what is in force.

    private static int configShow(CommandSourceStack source) {
        AutarkiaConfig config = Config.get();
        List<String> overrides = config.describeOverrides();
        source.sendSuccess(() -> Component.literal("Autarkia config — " + ConfigFile.path())
                .withStyle(ChatFormatting.AQUA), false);
        if (overrides.isEmpty()) {
            source.sendSuccess(() -> Component.literal("  all " + Knob.values().length
                    + " knobs at their defaults").withStyle(ChatFormatting.GRAY), false);
            return 1;
        }
        for (String line : overrides) {
            source.sendSuccess(() -> Component.literal("  " + line)
                    .withStyle(ChatFormatting.YELLOW), false);
        }
        return overrides.size();
    }

    private static int configReload(CommandSourceStack source) {
        List<String> problems = ConfigFile.reload();
        if (problems.isEmpty()) {
            source.sendSuccess(() -> Component.literal("Autarkia config reloaded — "
                    + Config.get().describeOverrides().size() + " override(s) in force")
                    .withStyle(ChatFormatting.GREEN), true);
            return 1;
        }
        source.sendSuccess(() -> Component.literal("Autarkia config reloaded with "
                + problems.size() + " problem(s):").withStyle(ChatFormatting.YELLOW), true);
        for (String problem : problems) {
            source.sendSuccess(() -> Component.literal("  " + problem)
                    .withStyle(ChatFormatting.RED), false);
        }
        return 1;
    }

    private static int configGet(CommandSourceStack source, String key) {
        Knob knob = Knob.byKey(key).orElse(null);
        if (knob == null) return unknownKnob(source, key);
        AutarkiaConfig config = Config.get();
        source.sendSuccess(() -> Component.literal(knob.key() + " = " + knob.format(config.get(knob))
                + (config.isDefault(knob) ? " (default)"
                        : " — default is " + knob.format(knob.def())))
                .withStyle(ChatFormatting.AQUA), false);
        source.sendSuccess(() -> Component.literal("  " + knob.doc())
                .withStyle(ChatFormatting.GRAY), false);
        source.sendSuccess(() -> Component.literal("  accepts " + knob.expects())
                .withStyle(ChatFormatting.DARK_GRAY), false);
        return 1;
    }

    private static int configSet(CommandSourceStack source, String key, String value) {
        Knob knob = Knob.byKey(key).orElse(null);
        if (knob == null) return unknownKnob(source, key);
        Double parsed = knob.parse(value).orElse(null);
        if (parsed == null) {
            source.sendFailure(Component.literal(knob.key() + " accepts " + knob.expects()
                    + " — \"" + value + "\" is not one"));
            return 0;
        }
        double clamped = knob.clamp(parsed);
        if (clamped != parsed) {
            source.sendSuccess(() -> Component.literal(knob.format(parsed) + " is outside "
                    + knob.expects() + " — clamped to " + knob.format(clamped))
                    .withStyle(ChatFormatting.YELLOW), false);
        }
        return applyAndSave(source, knob, Config.get().with(knob, parsed));
    }

    private static int configReset(CommandSourceStack source, String key) {
        Knob knob = Knob.byKey(key).orElse(null);
        if (knob == null) return unknownKnob(source, key);
        return applyAndSave(source, knob, Config.get().with(knob, knob.def()));
    }

    private static int configResetAll(CommandSourceStack source) {
        int had = Config.get().describeOverrides().size();
        Config.install(AutarkiaConfig.DEFAULTS);
        if (!ConfigFile.save(AutarkiaConfig.DEFAULTS)) {
            source.sendFailure(Component.literal("Reset in memory, but " + ConfigFile.path()
                    + " could not be written — the old values will come back on restart"));
            return 0;
        }
        source.sendSuccess(() -> Component.literal("Autarkia config reset to defaults ("
                + had + " override(s) cleared)").withStyle(ChatFormatting.GREEN), true);
        return 1;
    }

    /** Install + persist one changed knob, reporting what actually landed. */
    private static int applyAndSave(CommandSourceStack source, Knob knob, AutarkiaConfig updated) {
        Config.install(updated);
        double now = updated.get(knob);
        if (!ConfigFile.save(updated)) {
            source.sendFailure(Component.literal(knob.key() + " is now " + knob.format(now)
                    + " in memory, but " + ConfigFile.path()
                    + " could not be written — it will revert on restart"));
            return 0;
        }
        source.sendSuccess(() -> Component.literal(knob.key() + " = " + knob.format(now)
                + (updated.isDefault(knob) ? " (back to the default)" : ""))
                .withStyle(ChatFormatting.GREEN), true);
        return 1;
    }

    private static int unknownKnob(CommandSourceStack source, String key) {
        source.sendFailure(Component.literal("No such config key \"" + key
                + "\" — try tab-completion, or /autarkia config show"));
        return 0;
    }

    /** Runs {@link ObtainItem} (logs × count): rounds of scavenge-or-chop until the pack holds the
     *  quota, run to completion in one invocation. */
    private static int brainObtain(CommandSourceStack source, int count) {
        Person person = resolve(source);
        if (person == null) return 0;
        boolean autoDisabled = person.brain().run(new ObtainItem(ItemSpec.LOGS, count));
        String suffix = autoDisabledSuffix(autoDisabled);
        source.sendSuccess(() -> Component.literal(person.getName().getString() + ": "
                + person.brain().describe() + suffix).withStyle(ChatFormatting.AQUA), false);
        return 1;
    }

    /** Runs {@link ChopNearestTree} on the resolved Person — the full chop choreography against
     *  her nearest REMEMBERED grove (knowledge-driven: no memory of a tree, no chop). */
    private static int brainChop(CommandSourceStack source) {
        Person person = resolve(source);
        if (person == null) return 0;
        boolean autoDisabled = person.brain().run(new ChopNearestTree());
        String suffix = autoDisabledSuffix(autoDisabled);
        source.sendSuccess(() -> Component.literal(person.getName().getString() + ": "
                + person.brain().describe() + suffix).withStyle(ChatFormatting.AQUA), false);
        return 1;
    }

    /** Runs {@link SatisfyHunger} on the nearest Person — the first COMPOUND task, and the
     *  machinery the Eat instinct also drives (autonomously) via the arbiter. */
    private static int brainEat(CommandSourceStack source) {
        Person person = resolve(source);
        if (person == null) return 0;
        boolean autoDisabled = person.brain().run(new SatisfyHunger());
        String suffix = autoDisabledSuffix(autoDisabled);
        source.sendSuccess(() -> Component.literal(person.getName().getString() + ": "
                + person.brain().describe() + suffix).withStyle(ChatFormatting.AQUA), false);
        return 1;
    }

    private static int brainStatus(CommandSourceStack source) {
        Person person = resolve(source);
        if (person == null) return 0;
        source.sendSuccess(() -> Component.literal(person.getName().getString() + ": "
                + person.brain().describe()).withStyle(ChatFormatting.AQUA), false);
        return 1;
    }

    private static int brainCancel(CommandSourceStack source) {
        Person person = resolve(source);
        if (person == null) return 0;
        person.brain().cancel();
        source.sendSuccess(() -> Component.literal(person.getName().getString() + " task cancelled; "
                + person.brain().describe()).withStyle(ChatFormatting.AQUA), false);
        return 1;
    }

    /** Flips the nearest Person's autonomy switch and echoes the new describe() line (now
     *  reporting auto|manual up front). */
    private static int brainAuto(CommandSourceStack source, boolean auto) {
        Person person = resolve(source);
        if (person == null) return 0;
        person.brain().setAuto(auto);
        source.sendSuccess(() -> Component.literal(person.getName().getString() + ": "
                + person.brain().describe()).withStyle(ChatFormatting.AQUA), false);
        return 1;
    }

    /** The note appended to a manual {@code brain goto}/{@code brain eat} reply when that very
     *  call is what took the wheel from the arbiter. */
    private static String autoDisabledSuffix(boolean autoDisabled) {
        return autoDisabled ? " (auto disabled — re-enable with /autarkia brain auto true)" : "";
    }

    /** A {@code log <category>} branch: dumps only that subsystem's lines (optionally a count). */
    private static LiteralArgumentBuilder<CommandSourceStack> logCategory(String name, Category category) {
        return Commands.literal(name)
                .executes(ctx -> logDump(ctx.getSource(), category, DEFAULT_LOG_COUNT))
                .then(Commands.argument("count", IntegerArgumentType.integer(1))
                        .executes(ctx -> logDump(ctx.getSource(), category,
                                IntegerArgumentType.getInteger(ctx, "count"))));
    }

    /** As {@link #logCategory}, but under {@code log for <person>} — targets that resolved token. */
    private static LiteralArgumentBuilder<CommandSourceStack> logForCategory(String name, Category category) {
        return Commands.literal(name)
                .executes(ctx -> logFor(ctx.getSource(),
                        StringArgumentType.getString(ctx, "person"), category, DEFAULT_LOG_COUNT))
                .then(Commands.argument("count", IntegerArgumentType.integer(1))
                        .executes(ctx -> logFor(ctx.getSource(),
                                StringArgumentType.getString(ctx, "person"), category,
                                IntegerArgumentType.getInteger(ctx, "count"))));
    }

    /**
     * Dumps the journal of the {@link #resolve resolved} (pinned or nearest, and so necessarily
     * <em>loaded</em>) Person — the common case. {@code log for <name|id>} ({@link #logFor}) is the
     * variant that reaches an unloaded one.
     */
    private static int logDump(CommandSourceStack source, @Nullable Category category, int count) {
        Person person = resolve(source);
        if (person == null) return 0;
        PersonId id = person.getPersonId();
        if (id == null) {
            source.sendFailure(Component.literal("That Person isn't identified yet (still spawning)."));
            return 0;
        }
        return dumpJournal(source, id, person.getName().getString(), true, category, count);
    }

    /**
     * Dumps the journal of the person {@code token} names, resolved against the whole
     * {@link PersonDirectory} ({@link #resolveDirectory}): the ring is {@code PersonId}-keyed, so an
     * unloaded or never-spawned person still has one. Tagged {@code (not loaded)} when none is live.
     */
    private static int logFor(CommandSourceStack source, String token, @Nullable Category category, int count) {
        PersonId id = resolveDirectory(source, token);
        if (id == null) return 0;
        MinecraftServer server = source.getServer();
        String name = PersonDirectory.get(server).nameOf(id).orElse(shortId(id));
        boolean loaded = findLoaded(server, id) != null;
        return dumpJournal(source, id, name, loaded, category, count);
    }

    /**
     * The shared journal readout: the last {@code count} lines for {@code id} (newest last),
     * optionally filtered to one {@link Category}, read from the in-memory ring off the server-scoped
     * {@link Journals} service — the ephemeral tier; the full archive is the per-person file.
     */
    private static int dumpJournal(CommandSourceStack source, PersonId id, String name, boolean loaded,
                                   @Nullable Category category, int count) {
        List<Entry> all = Journals.of(source.getServer()).recent(id, Integer.MAX_VALUE); // whole ring; tail below
        List<Entry> matched = category == null ? all
                : all.stream().filter(entry -> entry.category() == category).toList();
        List<Entry> lines = matched.subList(Math.max(0, matched.size() - count), matched.size());
        String scope = category == null ? "" : " (" + category.name().toLowerCase(Locale.ROOT) + ")";
        String tag = loaded ? "" : " (not loaded)";
        if (lines.isEmpty()) {
            source.sendSuccess(() -> Component.literal(name + " has no" + scope + " log yet" + tag + ".")
                    .withStyle(ChatFormatting.GRAY), false);
            return 0;
        }
        source.sendSuccess(() -> Component.literal(name + " — last " + lines.size() + " lines" + scope + tag)
                .withStyle(ChatFormatting.AQUA), false);
        for (Entry entry : lines) {
            source.sendSuccess(() -> Component.literal(formatLine(name, entry))
                    .withStyle(colorFor(entry.category())), false);
        }
        return lines.size();
    }

    /**
     * Resolves a {@code name|id} token against the whole directory (loaded or not) to one
     * {@link PersonId}, or {@code null} having reported why: an id or short-id prefix first, then a
     * case-insensitive name. An ambiguous name fails hard, listing the candidates' short-ids —
     * there is no "nearest" to break the tie for an unloaded person.
     */
    private static @Nullable PersonId resolveDirectory(CommandSourceStack source, String rawToken) {
        String token = rawToken.trim();
        String lower = token.toLowerCase(Locale.ROOT);
        List<PersonIdentity> all = PersonDirectory.get(source.getServer()).all();
        List<PersonIdentity> matches = all.stream()
                .filter(identity -> identity.id().toString().toLowerCase(Locale.ROOT).startsWith(lower))
                .toList();
        if (matches.isEmpty()) {
            matches = all.stream().filter(identity -> identity.name().equalsIgnoreCase(token)).toList();
        }
        if (matches.isEmpty()) {
            source.sendFailure(Component.literal(
                    "No person matches '" + token + "' — try a name or id from /autarkia list."));
            return null;
        }
        if (matches.size() > 1) {
            String ids = matches.stream().map(identity -> shortId(identity.id()))
                    .collect(java.util.stream.Collectors.joining(", "));
            source.sendFailure(Component.literal(matches.size() + " persons named '" + token
                    + "' — pick one by id: " + ids));
            return null;
        }
        return matches.get(0).id();
    }

    /** One journal line, the {@code Bob - pathfind - target(…) - success N nodes} shape, tick-prefixed. */
    private static String formatLine(String name, Entry entry) {
        StringBuilder line = new StringBuilder()
                .append('[').append(entry.tick()).append("] ")
                .append(name).append(" - ").append(entry.category().name().toLowerCase(Locale.ROOT))
                .append(" - ").append(entry.event());
        if (!entry.detail().isEmpty()) {
            line.append(" - ").append(entry.detail());
        }
        return line.toString();
    }

    /**
     * Prints everything the resolved Person remembers — the knowledge store's debug view.
     * Beliefs, not world state: a listed grove may already be chopped (that is the staleness
     * the store is designed around), and "claimed blocks" is the transient dismissal index.
     */
    private static int knowledgeList(CommandSourceStack source) {
        Person person = resolve(source);
        if (person == null) return 0;
        PersonId id = person.getPersonId();
        if (id == null) {
            source.sendFailure(Component.literal("That Person isn't identified yet (still spawning)."));
            return 0;
        }
        PersonKnowledge knowledge = Knowledges.of(source.getServer()).forPerson(id);
        String name = person.getName().getString();
        if (knowledge.size() == 0) {
            source.sendSuccess(() -> Component.literal(name + " remembers no POIs yet.")
                    .withStyle(ChatFormatting.GRAY), false);
            return 0;
        }
        long now = source.getServer().overworld().getGameTime();
        source.sendSuccess(() -> Component.literal(name + " — " + knowledge.size()
                        + " remembered POI(s), " + person.poiSensor().claimCount() + " claimed blocks")
                .withStyle(ChatFormatting.AQUA), false);
        for (PoiKind kind : PoiKind.values()) {
            for (PoiMemory memory : knowledge.all(kind)) {
                String line = formatPoi(person, memory, now);
                source.sendSuccess(() -> Component.literal(line)
                        .withStyle(ChatFormatting.GREEN), false);
            }
        }
        return knowledge.size();
    }

    /** One belief line: {@code TREE (10, 64, 8) - 14 blocks away, 4 logs, seen 32s ago, partial}. */
    private static String formatPoi(Person person, PoiMemory memory, long now) {
        double distance = Math.sqrt(person.distanceToSqr(
                memory.anchor().x() + 0.5, memory.anchor().y() + 0.5, memory.anchor().z() + 0.5));
        long ageSeconds = memory.age(now) / 20;
        String age = ageSeconds < 2 ? "just now"
                : ageSeconds < 120 ? ageSeconds + "s ago"
                : (ageSeconds / 60) + "m ago";
        StringBuilder line = new StringBuilder(memory.kind().name())
                .append(" (").append(memory.anchor().x()).append(", ").append(memory.anchor().y())
                .append(", ").append(memory.anchor().z()).append(") - ")
                .append(Math.round(distance)).append(" blocks away, ")
                .append(memory.units()).append(memory.kind() == PoiKind.TREE ? " logs" : " cells")
                .append(", seen ").append(age);
        if (memory.partial()) {
            line.append(", partial");
        }
        return line.toString();
    }

    /**
     * Toggles the POI viewer for the resolved Person: particles on every remembered anchor +
     * bounds corner, and discovery chat routed to the toggling player (which is why "true" needs
     * a player source; "false" works from anywhere).
     */
    private static int knowledgeView(CommandSourceStack source, boolean on) {
        Person person = resolve(source);
        if (person == null) return 0;
        PersonId id = person.getPersonId();
        if (id == null) {
            source.sendFailure(Component.literal("That Person isn't identified yet (still spawning)."));
            return 0;
        }
        String name = person.getName().getString();
        if (on) {
            ServerPlayer player = source.getPlayer();
            if (player == null) {
                source.sendFailure(Component.literal(
                        "knowledge view true needs a player — the discovery chat goes to you."));
                return 0;
            }
            KnowledgeViewer.watch(source.getServer(), id, player.getUUID());
            source.sendSuccess(() -> Component.literal("Viewing " + name
                            + "'s knowledge — particles mark beliefs (ghosts included), discoveries land in your chat.")
                    .withStyle(ChatFormatting.GREEN), false);
        } else {
            boolean was = KnowledgeViewer.unwatch(source.getServer(), id);
            source.sendSuccess(() -> Component.literal(was
                            ? "Stopped viewing " + name + "'s knowledge."
                            : name + " wasn't being viewed.")
                    .withStyle(ChatFormatting.GRAY), false);
        }
        return 1;
    }

    private static ChatFormatting colorFor(Category category) {
        return switch (category) {
            case BODY -> ChatFormatting.RED;
            case PATHFIND -> ChatFormatting.AQUA;
            case BRAIN -> ChatFormatting.GOLD;
            case SENSE -> ChatFormatting.GREEN;
            case PROJECT -> ChatFormatting.LIGHT_PURPLE;
        };
    }

    /** Prints every non-empty slot of the nearest Person's inventory (storage + equipment). */
    private static int invList(CommandSourceStack source) {
        Person person = resolve(source);
        if (person == null) return 0;
        List<Inventory.Entry> occupied = person.inventory().occupied();
        if (occupied.isEmpty()) {
            source.sendSuccess(() -> Component.literal(person.getName().getString() + " carries nothing.")
                    .withStyle(ChatFormatting.GRAY), false);
            return 0;
        }
        source.sendSuccess(() -> Component.literal(person.getName().getString() + " carries:")
                .withStyle(ChatFormatting.AQUA), false);
        for (Inventory.Entry entry : occupied) {
            String line = "  " + slotLabel(entry.slot()) + "  " + entry.stack().id() + " x" + entry.stack().count();
            source.sendSuccess(() -> Component.literal(line).withStyle(ChatFormatting.GRAY), false);
        }
        return occupied.size();
    }

    /** Adds {@code count} of the given item to the nearest Person, reporting anything that didn't fit. */
    private static int invGive(CommandSourceStack source, ItemInput input, int count)
            throws CommandSyntaxException {
        Person person = resolve(source);
        if (person == null) return 0;
        // A count-1 template never trips the item argument's stack-size guard; the real count is set
        // in the core layer, which splits it across slots at the item's own cap. Components (from any
        // {…} the command carried) ride along via toCore.
        dev.luizloyola.autarkia.core.inv.ItemStack template =
                ItemStacks.templateOf(input, source.registryAccess());
        dev.luizloyola.autarkia.core.inv.ItemStack remainder = person.inventory().add(template.withCount(count));
        int placed = count - remainder.count();
        source.sendSuccess(() -> Component.literal(person.getName().getString() + " +" + placed + " "
                + template.id() + (remainder.isEmpty() ? "" : "  (" + remainder.count() + " didn't fit)"))
                .withStyle(ChatFormatting.AQUA), false);
        return placed;
    }

    /**
     * Moves one of the given item from storage into its natural equipment slot, returning any
     * piece it displaces to storage. The brain-less driver for wearing gear — like
     * {@code nav goto} is for walking.
     */
    private static int invEquip(CommandSourceStack source, ItemInput input) throws CommandSyntaxException {
        Person person = resolve(source);
        if (person == null) return 0;
        // The template resolves the kind + its natural slot only; the piece actually equipped is
        // pulled from storage below, so its own components (enchants, damage, …) are preserved.
        dev.luizloyola.autarkia.core.inv.ItemStack want =
                ItemStacks.templateOf(input, source.registryAccess());
        EquipmentSlot slot = ItemStacks.equipmentSlotOf(want);
        if (slot == null) {
            source.sendFailure(Component.literal(want.id() + " is not equippable."));
            return 0;
        }
        if (!PERSON_EQUIP_SLOTS.contains(slot)) { // e.g. BODY/SADDLE — no such slot on a Person
            source.sendFailure(Component.literal(want.id() + " can't be worn by a Person (" + slot.getName() + ")."));
            return 0;
        }
        Inventory inv = person.inventory();
        dev.luizloyola.autarkia.core.inv.ItemStack piece = inv.takeOne(want.id());
        if (piece.isEmpty()) {
            source.sendFailure(Component.literal(person.getName().getString() + " has no " + want.id() + " to equip."));
            return 0;
        }
        dev.luizloyola.autarkia.core.inv.ItemStack displaced = placeEquipment(inv, slot, piece);
        if (!displaced.isEmpty()) inv.add(displaced); // whatever was worn there goes back to storage
        source.sendSuccess(() -> Component.literal(person.getName().getString() + " equipped "
                + want.id() + " (" + slot.getName() + ")").withStyle(ChatFormatting.AQUA), false);
        return 1;
    }

    /** Places {@code stack} in the core inventory slot for {@code slot}, returning what was there. */
    private static dev.luizloyola.autarkia.core.inv.ItemStack placeEquipment(
            Inventory inv, EquipmentSlot slot, dev.luizloyola.autarkia.core.inv.ItemStack stack) {
        switch (slot) {
            case OFFHAND -> {
                dev.luizloyola.autarkia.core.inv.ItemStack prev = inv.offhand();
                inv.setOffhand(stack);
                return prev;
            }
            case MAINHAND -> {
                dev.luizloyola.autarkia.core.inv.ItemStack prev = inv.mainHand();
                inv.set(inv.selectedSlot(), stack);
                return prev;
            }
            default -> { // the four armor slots — HEAD/CHEST/LEGS/FEET names match ArmorType
                ArmorType type = ArmorType.valueOf(slot.name());
                dev.luizloyola.autarkia.core.inv.ItemStack prev = inv.armor(type);
                inv.setArmor(type, stack);
                return prev;
            }
        }
    }

    private static int invClear(CommandSourceStack source) {
        Person person = resolve(source);
        if (person == null) return 0;
        person.inventory().clear();
        source.sendSuccess(() -> Component.literal(person.getName().getString() + " inventory cleared.")
                .withStyle(ChatFormatting.AQUA), false);
        return 1;
    }

    /**
     * Attaches the optional {@code [<pos>] [name]} leaves to a spawn literal, so {@code spawn} and
     * its {@code nobrain} child share one argument shape and differ only in the brain flag they hand
     * {@link #personSpawn}.
     *
     * <p>Position mirrors {@code /summon}'s {@code <pos>}. The name is a NON-greedy string on
     * purpose: against coords like {@code 10 -59 5} a one-word name consumes only {@code 10} and
     * leaves {@code -59 5} unparsed, so that branch loses to the Vec3 one, while a bare {@code Bob}
     * falls to the name. Multi-word names must be quoted; a greedy name would swallow the line.
     */
    private static LiteralArgumentBuilder<CommandSourceStack> spawnLeaves(
            LiteralArgumentBuilder<CommandSourceStack> node, boolean autonomous) {
        return node
                .executes(ctx -> personSpawn(ctx.getSource(), null, null, autonomous))
                .then(Commands.argument("pos", Vec3Argument.vec3())
                        .executes(ctx -> personSpawn(ctx.getSource(),
                                Vec3Argument.getVec3(ctx, "pos"), null, autonomous))
                        .then(Commands.argument("name", StringArgumentType.string())
                                .executes(ctx -> personSpawn(ctx.getSource(),
                                        Vec3Argument.getVec3(ctx, "pos"),
                                        StringArgumentType.getString(ctx, "name"), autonomous))))
                .then(Commands.argument("name", StringArgumentType.string())
                        .executes(ctx -> personSpawn(ctx.getSource(), null,
                                StringArgumentType.getString(ctx, "name"), autonomous)));
    }

    /**
     * Spawns a new Person at {@code pos} (or the source's position when {@code null}), facing south
     * (yaw 0) like {@code /summon}. Directory-first: an identity — {@code name} if given, else
     * generated — is registered in the {@link PersonDirectory} and linked to the entity before it
     * enters the world. The entity is created before the directory is touched, so the common failure
     * (a null entity) leaves no orphan entry.
     *
     * <p>{@code autonomous} is the arbiter switch every Person spawns with ON; {@code person spawn
     * nobrain} passes {@code false} before the first tick, leaving an inert body still drivable via
     * {@code /autarkia brain}.
     */
    private static int personSpawn(CommandSourceStack source, @Nullable Vec3 pos, @Nullable String name,
                                   boolean autonomous) {
        String trimmed = name == null ? null : name.trim();
        if (trimmed != null && trimmed.isEmpty()) {
            source.sendFailure(Component.literal("Name must not be blank."));
            return 0;
        }
        ServerLevel level = source.getLevel();
        Person person = ModEntities.PERSON.create(level, EntitySpawnReason.COMMAND);
        if (person == null) {
            source.sendFailure(Component.literal("Could not create the Person entity."));
            return 0;
        }
        PersonDirectory directory = PersonDirectory.get(source.getServer());
        PersonIdentity identity = trimmed == null ? directory.createPerson() : directory.createPerson(trimmed);
        Vec3 spawnPos = pos != null ? pos : source.getPosition();
        // Yaw 0 = facing south, matching /summon (which keeps the entity's own default rotation);
        // pitch pinned flat since a Person stands upright (pitch is render-only head tilt, see face()).
        person.snapTo(spawnPos.x, spawnPos.y, spawnPos.z, 0.0F, 0.0F);
        person.assignPerson(identity.id());
        // "no brain": drop the arbiter into manual mode before the entity's first serverAiStep, so
        // it spawns inert.
        if (!autonomous) {
            person.brain().setAuto(false);
        }
        if (!level.addFreshEntity(person)) {
            source.sendFailure(Component.literal("Could not add the Person to the world."));
            return 0;
        }
        Appearance appearance = identity.appearance();
        String where = String.format(Locale.ROOT, "%.1f %.1f %.1f", spawnPos.x, spawnPos.y, spawnPos.z);
        String brainNote = autonomous ? "" : " — brain off (/autarkia brain auto true to enable)";
        source.sendSuccess(() -> Component.literal("Spawned ")
                .append(Component.literal(identity.name()).withStyle(ChatFormatting.AQUA))
                .append(Component.literal(" (" + appearance.gender() + ") at " + where + brainNote)
                        .withStyle(ChatFormatting.GRAY)), true);
        return 1;
    }

    /** Prints the nearest Person's need levels — the {@code needs().describe()} one-liner. */
    private static int personNeeds(CommandSourceStack source) {
        Person person = resolve(source);
        if (person == null) return 0;
        source.sendSuccess(() -> Component.literal(person.getName().getString() + ": "
                + person.needs().describe()).withStyle(ChatFormatting.AQUA), false);
        return 1;
    }

    /**
     * Sets the nearest Person's food level (0..20) and saturation (0.0 when omitted) and echoes
     * the readout — the dev knob for exercising starvation and regen without waiting out the burn.
     * Food is set before saturation, which clamps against it; exhaustion is zeroed so behaviour
     * afterwards is deterministic.
     */
    private static int personSetFood(CommandSourceStack source, int food, float saturation) {
        Person person = resolve(source);
        if (person == null) return 0;
        Needs needs = person.needs();
        needs.setFoodLevel(food);
        needs.setSaturation(saturation);
        needs.setExhaustion(0.0F);
        source.sendSuccess(() -> Component.literal(person.getName().getString() + ": "
                + needs.describe()).withStyle(ChatFormatting.AQUA), false);
        return 1;
    }

    /** Human-readable region label for a raw inventory slot index. */
    private static String slotLabel(int slot) {
        if (slot < Inventory.MAIN_START) return "hotbar[" + slot + "]";
        if (slot < Inventory.ARMOR_START) return "main[" + (slot - Inventory.MAIN_START) + "]";
        if (slot < Inventory.OFFHAND_SLOT) {
            return ArmorType.values()[slot - Inventory.ARMOR_START].name().toLowerCase(Locale.ROOT);
        }
        return "offhand";
    }

    private static Person nearest(CommandSourceStack source) {
        ServerLevel level = source.getLevel();
        Vec3 origin = source.getPosition();
        AABB box = AABB.ofSize(origin, NEAREST_RADIUS * 2, NEAREST_RADIUS * 2, NEAREST_RADIUS * 2);
        Person nearest = level.getEntitiesOfClass(Person.class, box).stream()
                .min((a, b) -> Double.compare(a.distanceToSqr(origin), b.distanceToSqr(origin)))
                .orElse(null);
        if (nearest == null) {
            source.sendFailure(Component.literal(
                    "No Person within " + (int) NEAREST_RADIUS + " blocks."));
        }
        return nearest;
    }

    /**
     * The target for a person-scoped command: the source's pinned Person, else the nearest
     * ({@link #nearest}). A pin that no longer resolves to a loaded entity is a hard failure, never
     * a silent fall-through. Returns {@code null} having reported the reason.
     */
    private static @Nullable Person resolve(CommandSourceStack source) {
        Optional<PersonId> pin = PersonSelection.pinned(source);
        if (pin.isEmpty()) return nearest(source);
        PersonId id = pin.get();
        Person live = findLoaded(source.getServer(), id);
        if (live == null) {
            source.sendFailure(Component.literal("Selected " + label(source.getServer(), id)
                    + " isn't loaded — /autarkia select clear, or select someone else."));
        }
        return live;
    }

    /** Suggests every loaded Person's name (quoted when it has spaces) and short id, so {@code select}
     *  tab-completes to something that actually resolves. */
    /** Every knob's dotted key — the completions behind {@code config get/set/reset}. */
    private static final SuggestionProvider<CommandSourceStack> KNOB_SUGGESTIONS = (ctx, builder) ->
            SharedSuggestionProvider.suggest(Stream.of(Knob.values()).map(Knob::key), builder);

    private static final SuggestionProvider<CommandSourceStack> PERSON_SUGGESTIONS = (ctx, builder) -> {
        MinecraftServer server = ctx.getSource().getServer();
        PersonDirectory directory = PersonDirectory.get(server);
        Stream<String> tokens = loadedPersons(server).stream().flatMap(person -> {
            PersonId id = person.getPersonId();
            if (id == null) return Stream.empty();
            String shortId = shortId(id);
            return directory.nameOf(id)
                    .map(name -> Stream.of(name.contains(" ") ? '"' + name + '"' : name, shortId))
                    .orElseGet(() -> Stream.of(shortId));
        });
        return SharedSuggestionProvider.suggest(tokens, builder);
    };

    /** Suggests every registered person's name + short id (loaded or not) for {@code log for},
     *  which (unlike {@code select}) can reach an unloaded person's journal. */
    private static final SuggestionProvider<CommandSourceStack> ALL_PERSON_SUGGESTIONS = (ctx, builder) -> {
        Stream<String> tokens = PersonDirectory.get(ctx.getSource().getServer()).all().stream()
                .flatMap(identity -> Stream.of(
                        identity.name().contains(" ") ? '"' + identity.name() + '"' : identity.name(),
                        shortId(identity.id())));
        return SharedSuggestionProvider.suggest(tokens, builder);
    };

    /** Pins the Person named (or short-id-prefixed) by {@code rawToken}. */
    private static int selectByToken(CommandSourceStack source, String rawToken) {
        String token = rawToken.trim();
        MinecraftServer server = source.getServer();
        PersonDirectory directory = PersonDirectory.get(server);
        Vec3 origin = source.getPosition();
        List<Person> loaded = loadedPersons(server);

        // An id (or short-id prefix) is an unambiguous handle, so it's tried first; only if nothing
        // matches by id do we fall back to a case-insensitive name match (names aren't unique).
        String lower = token.toLowerCase(Locale.ROOT);
        List<Person> matches = loaded.stream()
                .filter(p -> p.getPersonId() != null
                        && p.getPersonId().toString().toLowerCase(Locale.ROOT).startsWith(lower))
                .toList();
        if (matches.isEmpty()) {
            matches = loaded.stream()
                    .filter(p -> p.getPersonId() != null
                            && directory.nameOf(p.getPersonId()).map(n -> n.equalsIgnoreCase(token)).orElse(false))
                    .toList();
        }
        if (matches.isEmpty()) {
            source.sendFailure(Component.literal(
                    "No loaded Person matches '" + token + "' — try /autarkia list."));
            return 0;
        }
        int count = matches.size();
        Person chosen = matches.stream()
                .min((a, b) -> Double.compare(a.distanceToSqr(origin), b.distanceToSqr(origin)))
                .orElseThrow();
        PersonId id = chosen.getPersonId();
        PersonSelection.pin(source, id);
        source.sendSuccess(() -> Component.literal("Selected " + label(server, id)
                + (count > 1 ? " (nearest of " + count + " matches)" : "")).withStyle(ChatFormatting.AQUA), false);
        return 1;
    }

    /** No-argument {@code select}: a player pins the Person under their crosshair, or unpins when
     *  looking at nobody; the console (or any non-player source) pins the nearest one. */
    private static int selectHere(CommandSourceStack source) {
        boolean fromPlayer = source.getEntity() instanceof ServerPlayer;
        Person target = source.getEntity() instanceof ServerPlayer player ? lookedAt(player) : nearest(source);
        if (target == null) {
            if (fromPlayer) {
                // Looking at nobody clears any current pin — the same as `select clear`.
                return selectClear(source);
            }
            // the console branch already reported via nearest()
            return 0;
        }
        PersonId id = target.getPersonId();
        if (id == null) {
            source.sendFailure(Component.literal("That Person isn't identified yet (still spawning)."));
            return 0;
        }
        PersonSelection.pin(source, id);
        source.sendSuccess(() -> Component.literal("Selected " + label(source.getServer(), id))
                .withStyle(ChatFormatting.AQUA), false);
        return 1;
    }

    private static int selectClear(CommandSourceStack source) {
        if (PersonSelection.clear(source)) {
            source.sendSuccess(() -> Component.literal("Selection cleared — commands use the nearest Person again.")
                    .withStyle(ChatFormatting.AQUA), false);
            return 1;
        }
        source.sendSuccess(() -> Component.literal("No Person was selected.").withStyle(ChatFormatting.GRAY), false);
        return 0;
    }

    private static int selectShow(CommandSourceStack source) {
        Optional<PersonId> pin = PersonSelection.pinned(source);
        if (pin.isEmpty()) {
            source.sendSuccess(() -> Component.literal("No selection — commands use the nearest Person.")
                    .withStyle(ChatFormatting.GRAY), false);
            return 0;
        }
        PersonId id = pin.get();
        boolean loaded = findLoaded(source.getServer(), id) != null;
        source.sendSuccess(() -> Component.literal("Selected: " + label(source.getServer(), id)
                + (loaded ? "" : " (not loaded)")).withStyle(loaded ? ChatFormatting.AQUA : ChatFormatting.GRAY), false);
        return loaded ? 1 : 0;
    }

    /** Lists the loaded Persons, nearest first: a {@code ✓} on the pinned one, then name, short id,
     *  dimension, and distance. This is how you find out what to {@code select}. */
    private static int listPersons(CommandSourceStack source) {
        MinecraftServer server = source.getServer();
        PersonDirectory directory = PersonDirectory.get(server);
        Vec3 origin = source.getPosition();
        List<Person> loaded = loadedPersons(server);
        if (loaded.isEmpty()) {
            source.sendSuccess(() -> Component.literal("No Persons are loaded.").withStyle(ChatFormatting.GRAY), false);
            return 0;
        }
        Optional<PersonId> pin = PersonSelection.pinned(source);
        loaded.stream()
                .sorted((a, b) -> Double.compare(a.distanceToSqr(origin), b.distanceToSqr(origin)))
                .forEach(person -> {
                    PersonId id = person.getPersonId();
                    boolean isPinned = id != null && pin.map(id::equals).orElse(false);
                    String name = id == null ? "<spawning>" : directory.nameOf(id).orElse("<unknown>");
                    String dimension = person.level().dimension().identifier().getPath();
                    double distance = Math.sqrt(person.distanceToSqr(origin));
                    String line = String.format(Locale.ROOT, "%s%s  %s  %s  %.1fm",
                            isPinned ? "✓ " : "  ", name, id == null ? "-" : shortId(id), dimension, distance);
                    source.sendSuccess(() -> Component.literal(line)
                            .withStyle(isPinned ? ChatFormatting.AQUA : ChatFormatting.GRAY), false);
                });
        return loaded.size();
    }

    /** The Person a player's crosshair is on, within {@link #NEAREST_RADIUS}, or {@code null}. */
    private static @Nullable Person lookedAt(ServerPlayer player) {
        Vec3 eye = player.getEyePosition();
        Vec3 far = eye.add(player.getViewVector(1.0F).scale(NEAREST_RADIUS));
        AABB search = player.getBoundingBox().expandTowards(player.getViewVector(1.0F).scale(NEAREST_RADIUS)).inflate(1.0);
        EntityHitResult hit = ProjectileUtil.getEntityHitResult(
                player, eye, far, search, e -> e instanceof Person, NEAREST_RADIUS * NEAREST_RADIUS);
        return hit != null && hit.getEntity() instanceof Person person ? person : null;
    }

    /** Every live Person across every dimension. {@code getEntities} still hands back a Person killed
     *  moments ago (it lingers through its death animation before being swept), so {@code isAlive}
     *  is filtered on, or {@code list} and the resolver would act on a corpse. */
    private static List<Person> loadedPersons(MinecraftServer server) {
        List<Person> out = new ArrayList<>();
        for (ServerLevel level : server.getAllLevels()) {
            out.addAll(level.getEntities(ModEntities.PERSON, Person::isAlive));
        }
        return out;
    }

    /** The live Person with this id, searching every dimension, or {@code null} if none is loaded.
     *  A dead/dying Person (not yet swept) does not count — see {@link #loadedPersons}. */
    private static @Nullable Person findLoaded(MinecraftServer server, PersonId id) {
        for (ServerLevel level : server.getAllLevels()) {
            for (Person person : level.getEntities(ModEntities.PERSON, p -> p.isAlive() && id.equals(p.getPersonId()))) {
                return person;
            }
        }
        return null;
    }

    /** The person's name if the directory knows it, else the short id — a stable label for messages. */
    private static String label(MinecraftServer server, PersonId id) {
        return PersonDirectory.get(server).nameOf(id).orElse(shortId(id));
    }

    /** The first 8 characters of an id — enough to eyeball and to prefix-match in {@code select}. */
    private static String shortId(PersonId id) {
        String text = id.toString();
        return text.substring(0, Math.min(8, text.length()));
    }

    private static int whoisNearest(CommandSourceStack source) {
        Person target = resolve(source);
        if (target == null) return 0;
        report(source, target);
        return 1;
    }

    private static int whoisTargets(CommandSourceStack source,
                                    java.util.Collection<? extends Entity> targets) {
        List<Person> persons = targets.stream().filter(e -> e instanceof Person).map(e -> (Person) e).toList();
        if (persons.isEmpty()) {
            source.sendFailure(Component.literal("No Person among the selected entities."));
            return 0;
        }
        persons.forEach(person -> report(source, person));
        return persons.size();
    }

    private static void report(CommandSourceStack source, Person person) {
        PersonId id = person.getPersonId();
        if (id == null) {
            source.sendSuccess(() -> Component.literal("Person not yet identified (spawning).")
                    .withStyle(ChatFormatting.GRAY), false);
            return;
        }
        PersonIdentity identity = PersonDirectory.get(source.getServer()).find(id).orElse(null);
        if (identity == null) {
            source.sendSuccess(() -> Component.literal(id + "  <unknown>").withStyle(ChatFormatting.GRAY), false);
            return;
        }
        Appearance appearance = identity.appearance();
        // Full tier (name) + external tier (gender, skin) + the stable id.
        ChatFormatting genderColor = appearance.gender().choose(ChatFormatting.BLUE, ChatFormatting.LIGHT_PURPLE);
        MutableComponent line = Component.literal(identity.name()).withStyle(ChatFormatting.AQUA)
                .append(Component.literal(" " + appearance.gender()).withStyle(genderColor))
                .append(Component.literal(" " + appearance.model()).withStyle(ChatFormatting.DARK_AQUA))
                .append(Component.literal("  " + appearance.skin()).withStyle(ChatFormatting.GRAY))
                .append(Component.literal("  " + id).withStyle(ChatFormatting.DARK_GRAY));
        source.sendSuccess(() -> line, false);
    }
}
