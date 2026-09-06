package dev.luizloyola.autarkia.mod.command;

import java.util.Map;
import dev.luizloyola.anima.mod.identity.AgentDirectory;
import dev.luizloyola.anima.mod.identity.AgentRecords;
import dev.luizloyola.anima.core.agent.PrivateIdentity;
import dev.luizloyola.anima.mod.body.AgentBodies;
import dev.luizloyola.anima.mod.body.AgentBody;
import dev.luizloyola.anima.mod.command.AgentCommands;
import dev.luizloyola.anima.mod.command.AgentSelection;
import dev.luizloyola.anima.mod.command.OpJournal;
import dev.luizloyola.anima.mod.command.Replies;
import dev.luizloyola.anima.mod.command.Subject;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.FloatArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import dev.luizloyola.anima.compat.inv.ItemStacks;
import dev.luizloyola.anima.core.brain.knowledge.AgentKnowledge;
import dev.luizloyola.anima.core.brain.knowledge.PoiKind;
import dev.luizloyola.anima.core.brain.knowledge.PoiMemory;
import dev.luizloyola.anima.core.brain.sense.Being;
import dev.luizloyola.anima.core.brain.task.BreakBlock;
import dev.luizloyola.anima.core.brain.task.GoTo;
import dev.luizloyola.anima.core.brain.task.ObtainItem;
import dev.luizloyola.anima.core.brain.task.Producers;
import dev.luizloyola.anima.core.brain.task.SatisfyHunger;
import dev.luizloyola.anima.core.config.ConfigValues;
import dev.luizloyola.anima.mod.command.CommandSurface;
import dev.luizloyola.anima.mod.command.ConfigCommands;
import dev.luizloyola.anima.mod.config.ConfigFile;
import dev.luizloyola.autarkia.core.config.AutarkiaConfig;
import dev.luizloyola.anima.core.config.Config;
import dev.luizloyola.anima.core.config.Knob;
import dev.luizloyola.anima.core.inv.ArmorType;
import dev.luizloyola.anima.core.inv.Inventory;
import dev.luizloyola.anima.core.inv.ItemSpec;
import dev.luizloyola.autarkia.core.board.Board;
import dev.luizloyola.autarkia.core.board.CarrySplit;
import dev.luizloyola.autarkia.core.board.ClearArea;
import dev.luizloyola.autarkia.core.board.Gather;
import dev.luizloyola.autarkia.core.board.PartyBoard;
import dev.luizloyola.autarkia.core.board.Stock;
import dev.luizloyola.autarkia.core.tree.TreeClearing;
import dev.luizloyola.autarkia.mod.board.PartyBoards;
import dev.luizloyola.autarkia.core.tree.FellTree;
import dev.luizloyola.autarkia.core.tree.Pois;
import dev.luizloyola.anima.core.brain.knowledge.Region;
import dev.luizloyola.anima.core.brain.sense.Pos;
import dev.luizloyola.anima.core.log.Category;
import dev.luizloyola.anima.core.log.Entry;
import dev.luizloyola.autarkia.core.person.Appearance;
import dev.luizloyola.anima.core.agent.Metabolism;
import dev.luizloyola.anima.core.agent.AgentId;
import dev.luizloyola.autarkia.core.person.PersonIdentity;
import dev.luizloyola.anima.mod.brain.KnowledgeViewer;
import dev.luizloyola.anima.mod.brain.Knowledges;
import dev.luizloyola.anima.mod.brain.BeingViewer;
import dev.luizloyola.anima.mod.debug.DebugLayer;
import dev.luizloyola.anima.mod.debug.DebugView;
import dev.luizloyola.autarkia.mod.debug.BoardViewer;
import dev.luizloyola.autarkia.mod.debug.TreeSplitViewer;
import dev.luizloyola.autarkia.mod.entity.ModEntities;
import dev.luizloyola.autarkia.mod.entity.Persons;
import dev.luizloyola.autarkia.mod.entity.Person;
import dev.luizloyola.anima.mod.log.ThoughtBroadcast;
import dev.luizloyola.anima.mod.net.ContactsSync;
import dev.luizloyola.autarkia.mod.person.PersonDirectory;
import dev.luizloyola.anima.core.social.PartyId;
import dev.luizloyola.anima.mod.social.ContactData;
import dev.luizloyola.anima.mod.social.PartyData;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandBuildContext;
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
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Developer/admin commands for inspecting Autarkia state.
 *
 * <p>{@code whois [targets]} prints the resolved {@code Person}'s full identity: the name is never
 * synced to clients, so it is read from the server-side {@link PersonDirectory} on request.
 *
 * <p>{@code nav} drives the resolved Person's legs directly (locomotion debug, exercisable from a
 * headless dev server and by command blocks); {@code brain} runs a task through the executor the
 * arbiter feeds. {@code brain auto true|false} flips autonomy — ON, every Person's default, is the
 * arbiter deciding on its own; OFF is the dev override, which a manual {@code goto}/{@code eat}
 * also sets. {@code brain wander true|false} is narrower: the arbiter keeps deciding but the idle
 * wander drive bids nothing, and the pressure line reads {@code wander (muted) 0.00} so a mute
 * never reads as a coincidence.
 *
 * <p><b>Every {@code true|false} switch reads back when you leave the value off</b> — a READ, not
 * a blind toggle, because you ask precisely when you have lost track of the state. Each returns 1
 * for on and 0 for off, usable from a command block or an {@code execute if}.
 *
 * <p>{@code log} reads the in-memory ring, interleaved with no subsystem given and filtered with
 * one; {@code log for <name|id>} reaches any person by directory lookup, including one whose
 * entity is unloaded (the ring is {@code AgentId}-keyed and outlives the entity), tagged
 * {@code (not loaded)}.
 *
 * <p>{@code person spawn [<pos>] [name]} registers an identity in the {@link PersonDirectory} and
 * links the entity to it before spawn; a plain {@code /summon autarkia:person} mints its own a
 * tick later instead. {@code spawn nobrain} starts with autonomy off, {@code spawn nowander} with
 * the idle drift muted.
 *
 * <p>Person-scoped subcommands resolve through {@link #resolve}: the Person the command runs
 * <em>as</em> (one line, every Person in turn), else the source's selected Person, else the nearest.
 * Bare {@code select} takes the Person a player is looking at, clears when looking at nobody, and
 * takes the nearest from the console; {@code list} exists because names are not unique. Selections
 * live in {@link AgentSelection} — in memory, per source, gone on restart.
 */
public final class AutarkiaCommands {
    private AutarkiaCommands() {}

    private static final double NEAREST_RADIUS = 32.0;

    /**
     * Registers {@code /autarkia …}. <b>Op-gated whole, matching {@code /anima}</b> — every node
     * under it either drives a Person, spawns or erases one, or reads out the machinery, so there
     * is none an ordinary player wants and several a shared server should not hand out. Brigadier
     * drops a failing root from the tree, so a non-op does not see it at all.
     */
    public static void register(ConfigFile configFile) {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
                dispatcher.register(CommandSurface.mount(
                        Commands.literal("autarkia")
                                .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS)),
                        // hasSubject — mounted at the root AND under `as <person>`. Anima's shared
                        // tree, plus the two brain leaves and the board that are ours: a log quota
                        // and a work ledger are facts about being a settler, not about thinking.
                        List.of(AgentCommands::select, AgentCommands::contacts, AgentCommands::party,
                                AgentCommands::places, AgentCommands::nav, AgentCommands::follow,
                                () -> AgentCommands.brain().then(chop()).then(obtain(registryAccess)),
                                AgentCommands::think, AgentCommands::log, AgentCommands::knowledge,
                                AgentCommands::horizon, AgentCommands::survey, AgentCommands::claims,
                                AgentCommands::peers, AgentCommands::needs, AgentCommands::profile,
                                AgentCommands::grave, AgentCommands::chat, AutarkiaCommands::whois,
                                () -> board(registryAccess),
                                () -> AgentCommands.inv(registryAccess)),
                        // noSubject — the root alone. `tree` paints the live world, `spawn` makes a
                        // body there is not one of yet, and `debug` and `board view` are per-player
                        // switches that vary with nothing about a subject.
                        List.of(AgentCommands::list, AgentCommands::debug,
                                AutarkiaCommands::whoisTargets, AutarkiaCommands::tree,
                                AutarkiaCommands::spawn, AutarkiaCommands::boardViewNode,
                                () -> ConfigCommands.tree(AutarkiaConfig.store(), configFile)),
                        // asOnly — must name its subject; see erase().
                        List.of(AutarkiaCommands::erase))));
    }

    /** Point the subject at the nearest remembered tree, or at a named one. */
    private static LiteralArgumentBuilder<CommandSourceStack> chop() {
        return Commands.literal("chop")
                                        .executes(ctx -> brainChop(ctx, null))
                                        .then(Commands.argument("pos", BlockPosArgument.blockPos())
                                                .executes(ctx -> brainChop(ctx,
                                                        BlockPosArgument.getBlockPos(
                                                                ctx, "pos"))));
    }

    /** A log quota, and the craft verb. Anima has no idea what a settler wants. */
    private static LiteralArgumentBuilder<CommandSourceStack> obtain(CommandBuildContext registryAccess) {
        return Commands.literal("obtain")
                                        .then(Commands.literal("logs")
                                                .executes(ctx -> brainObtain(ctx, 16))
                                                .then(Commands.argument("count", IntegerArgumentType.integer(1))
                                                        .executes(ctx -> brainObtain(ctx,
                                                                IntegerArgumentType.getInteger(ctx, "count")))))
                                        // Any concrete item, by id — an exact-item spec built on
                                        // the spot (ItemSpec.anyOf: canonical and persistable, so
                                        // the order survives a reload). Also the craft verb:
                                        // "obtain stick 4" plans the whole planks-then-sticks
                                        // chain out of one carried log.
                                        .then(Commands.argument("item", ItemArgument.item(registryAccess))
                                                .executes(ctx -> brainObtainItem(ctx,
                                                        ItemArgument.getItem(ctx, "item"), 1))
                                                .then(Commands.argument("count", IntegerArgumentType.integer(1))
                                                        .executes(ctx -> brainObtainItem(ctx,
                                                                ItemArgument.getItem(ctx, "item"),
                                                                IntegerArgumentType.getInteger(ctx, "count")))));
    }

    /** Bare {@code whois} — what the SUBJECT looks like. */
    private static LiteralArgumentBuilder<CommandSourceStack> whois() {
        return Commands.literal("whois")
                                .executes(ctx -> whoisResolved(ctx));
    }

    /**
     * {@code whois <targets>} — a bulk readout over arbitrary entities, so it takes an OBJECT and
     * has no subject. Two literal children of one name MERGE in Brigadier, so the root ends up with
     * a single {@code whois} node carrying this and the bare form both, while the {@code as} seam
     * carries only the bare one.
     */
    private static LiteralArgumentBuilder<CommandSourceStack> whoisTargets(){
        return Commands.literal("whois")
                                .then(Commands.argument("targets", EntityArgument.entities())
                                        .executes(ctx -> whoisTargets(ctx.getSource(),
                                                EntityArgument.getEntities(ctx, "targets"))));
    }

    /** How the ground would carve into trees — no Person or perception involved. */
    private static LiteralArgumentBuilder<CommandSourceStack> tree() {
        return Commands.literal("tree")
                                .then(Commands.literal("view")
                                        .executes(ctx -> treeView(ctx.getSource(), 0))
                                        .then(Commands.argument("radius",
                                                        IntegerArgumentType.integer(4, 32))
                                                .executes(ctx -> treeView(ctx.getSource(),
                                                        IntegerArgumentType.getInteger(
                                                                ctx, "radius")))));
    }

    /**
     * Layer 3's ledger. Bare, it reads both boards the subject can reach; every verb below hangs
     * off the board it acts on. Scoped rather than merged because the two boards number their
     * projects from one, so both readouts show a {@code #1} and a leaf that did not name its board
     * could only guess — see {@link #boardCancel}.
     */
    private static LiteralArgumentBuilder<CommandSourceStack> board(CommandBuildContext registryAccess) {
        return Commands.literal("board")
                .executes(ctx -> boardShow(ctx))
                .then(boardPersonalNode())
                .then(boardPartyNode(registryAccess));
    }

    /**
     * The subject's own board. Nothing posts here — its projects are the standing wants a settler
     * grows for itself — so reading and dropping one are the whole surface.
     */
    private static LiteralArgumentBuilder<CommandSourceStack> boardPersonalNode() {
        return Commands.literal("personal")
                .executes(ctx -> boardScope(ctx, false))
                .then(Commands.literal("cancel")
                        .then(Commands.argument("handle", IntegerArgumentType.integer(1))
                                .executes(ctx -> boardCancel(ctx,
                                        IntegerArgumentType.getInteger(ctx, "handle"), false))));
    }

    /** The board the subject's party shares — the only one an operator posts work to. */
    private static LiteralArgumentBuilder<CommandSourceStack> boardPartyNode(CommandBuildContext registryAccess) {
        return Commands.literal("party")
                .executes(ctx -> boardScope(ctx, true))
                // Two corners and nothing else — the box is the whole brief; who goes in, in what
                // order, and how they learn what is standing there is the project's business, not
                // the operator's.
                .then(Commands.literal("post")
                        .then(Commands.literal("clear")
                                .then(Commands.argument("from", BlockPosArgument.blockPos())
                                        .then(Commands.argument("to", BlockPosArgument.blockPos())
                                                .executes(ctx -> boardPostClear(ctx,
                                                        corner(ctx, "from"), corner(ctx, "to"),
                                                        CLEAR_PRIORITY, null))
                                                .then(Commands.argument("priority",
                                                                DoubleArgumentType.doubleArg(0.0, 1.0))
                                                        .executes(ctx -> boardPostClear(ctx,
                                                                corner(ctx, "from"), corner(ctx, "to"),
                                                                DoubleArgumentType.getDouble(ctx, "priority"),
                                                                null)))
                                                // Where the wood goes. A HINT, not a cell to obey: the
                                                // first hauler opens a chest on whatever ground near it
                                                // will hold one, and the readout names where it went.
                                                .then(Commands.literal("at")
                                                        .then(Commands.argument("yard", BlockPosArgument.blockPos())
                                                                .executes(ctx -> boardPostClear(ctx,
                                                                        corner(ctx, "from"), corner(ctx, "to"),
                                                                        CLEAR_PRIORITY, corner(ctx, "yard")))
                                                                .then(Commands.argument("priority",
                                                                                DoubleArgumentType.doubleArg(0.0, 1.0))
                                                                        .executes(ctx -> boardPostClear(ctx,
                                                                                corner(ctx, "from"), corner(ctx, "to"),
                                                                                DoubleArgumentType.getDouble(ctx, "priority"),
                                                                                corner(ctx, "yard")))))))))
                        // Get this many of this item into that yard. `at` is MANDATORY here (unlike
                        // clear's) — a gather with nowhere to put the goods has no completion rule —
                        // so this is two leaves, not four.
                        .then(Commands.literal("gather")
                                .then(Commands.argument("item", ItemArgument.item(registryAccess))
                                        .then(Commands.argument("count", IntegerArgumentType.integer(1))
                                                .then(Commands.literal("at")
                                                        .then(Commands.argument("pos", BlockPosArgument.blockPos())
                                                                .executes(ctx -> boardPostGather(ctx,
                                                                        ItemArgument.getItem(ctx, "item"),
                                                                        IntegerArgumentType.getInteger(ctx, "count"),
                                                                        corner(ctx, "pos"), GATHER_PRIORITY))
                                                                .then(Commands.argument("priority",
                                                                                DoubleArgumentType.doubleArg(0.0, 1.0))
                                                                        .executes(ctx -> boardPostGather(ctx,
                                                                                ItemArgument.getItem(ctx, "item"),
                                                                                IntegerArgumentType.getInteger(ctx, "count"),
                                                                                corner(ctx, "pos"),
                                                                                DoubleArgumentType.getDouble(
                                                                                        ctx, "priority"))))))))))
                // Every row of the ledger, one line each — the only way to ask "did that tree
                // actually go?" of the world afterwards.
                .then(Commands.literal("targets")
                        .executes(ctx -> boardTargets(ctx)))
                .then(Commands.literal("cancel")
                        .then(Commands.argument("handle", IntegerArgumentType.integer(1))
                                .executes(ctx -> boardCancel(ctx,
                                        IntegerArgumentType.getInteger(ctx, "handle"), true))));
    }

    /**
     * {@code board view} — a per-player overlay of every project in RANGE rather than one person's
     * board, so nothing about it varies with a subject and it stays at the root.
     */
    private static LiteralArgumentBuilder<CommandSourceStack> boardViewNode() {
        return Commands.literal("board")
                .then(Commands.literal("view")
                        .executes(ctx -> boardView(ctx.getSource(), null))
                        .then(Commands.argument("on", BoolArgumentType.bool())
                                .executes(ctx -> boardView(ctx.getSource(),
                                        BoolArgumentType.getBool(ctx, "on")))));
    }

    /**
     * Spawning, flattened out of the old {@code person} group: {@code spawn} is autonomous,
     * {@code spawn nobrain} starts with autonomy off, {@code spawn nowander} thinks normally but
     * never drifts. All three take the same {@code [<pos>] [name]} leaves.
     */
    private static LiteralArgumentBuilder<CommandSourceStack> spawn() {
        return spawnLeaves(Commands.literal("spawn"), Mind.FULL)
                                .then(spawnLeaves(Commands.literal("nobrain"), Mind.NO_BRAIN))
                                .then(spawnLeaves(Commands.literal("nowander"), Mind.NO_WANDER));
    }

    /**
     * {@code as <person> erase} — destroys an identity, its directory entry, knowledge and journal.
     *
     * <p>Mounted under {@code as} ALONE. A bare form would fall down the resolve ladder to whoever
     * happens to be standing nearest, and this is the one verb in either tree that cannot be undone.
     * It replaced {@code purge graveyard} (removed 2026-08-04), which called every identity with no
     * LOADED entity dead and so deleted most of a settlement on any world where settlers leave
     * render distance — absence never meant death (2026-08-03-persistence-design.md).
     */
    private static LiteralArgumentBuilder<CommandSourceStack> erase() {
        return Commands.literal("erase").executes(AutarkiaCommands::personErase);
    }

    /**
     * Prints both boards the resolved Person can reach (their own and their party's) one row per
     * project, with its handle, its state and how many of its items are claimed.
     *
     * <p>Both, because a summary could not tell a quiet personal board from an empty party one.
     * {@link #boardScope} is the same readout narrowed to the board the caller named.
     */
    private static int boardShow(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        Person person = resolve(ctx);
        if (person == null) return 0;
        Replies.send(source, () -> Component.translatable("autarkia.command.board.header",
                person.getName()).withStyle(ChatFormatting.LIGHT_PURPLE));
        // The rows themselves are describeBoard()'s, which the journal and the debug feed read
        // too — one shape for all three rather than a second one only chat would see.
        for (String line : person.brain().describeBoard()) {
            Replies.send(source, () -> indent(Component.literal(line)
                    .withStyle(ChatFormatting.LIGHT_PURPLE)));
        }
        return 1;
    }

    /**
     * One named board's rows — {@code board personal} or {@code board party}, the halves the bare
     * readout stacks.
     *
     * <p>Reads the board's own {@code describeLines} against {@code getGameTime()}, which is the
     * tick {@code Percepts.time()} reports: the holds it counts are measured against that clock and
     * a readout on any other one would age them wrong.
     */
    private static int boardScope(CommandContext<CommandSourceStack> ctx, boolean party) {
        CommandSourceStack source = ctx.getSource();
        Person person = resolve(ctx);
        if (person == null) return 0;
        Board board;
        if (party) {
            Optional<PartyBoard> theirs = partyBoardOf(person);
            if (theirs.isEmpty()) {
                Replies.fail(source, Component.translatable(
                        "autarkia.command.no_identity", person.getName()));
                return 0;
            }
            board = theirs.get();
        } else {
            board = person.board();
        }
        Replies.send(source, () -> Component.translatable(party
                                ? "autarkia.command.board.header_party"
                                : "autarkia.command.board.header_personal",
                        person.getName())
                .withStyle(ChatFormatting.LIGHT_PURPLE));
        for (String line : board.describeLines(person.level().getGameTime())) {
            Replies.send(source, () -> indent(Component.literal(line)
                    .withStyle(ChatFormatting.LIGHT_PURPLE)));
        }
        return 1;
    }

    /**
     * Default bid for a posted clearing. Above the personal board's standing want (0.35) on
     * purpose: it should win at close range while still losing to a settler who is genuinely
     * hungry.
     */
    private static final double CLEAR_PRIORITY = 0.5;

    /** Default bid for a posted gather — the same scale {@link Gather#COST_RANGE} prices its
     *  errands on (deliberately equal to {@link ClearArea}'s), so the two kinds of party work
     *  compete fairly by default. */
    private static final double GATHER_PRIORITY = 0.5;

    /** One line nested under the one above it — see {@code ConfigCommands.indent}. */
    private static Component indent(Component line) {
        return Component.literal("  ").append(line);
    }

    /** Longest side one clearing project takes, in blocks. See the refusal for why. */
    private static final int CLEAR_MAX_SIDE = 512;

    /** Everyone whose work a party-wide command reached — empty before they know who they are. */
    private static List<AgentId> partyMembers(CommandSourceStack source, Person person) {
        AgentId who = person.getAgentId();
        if (who == null) {
            return List.of();
        }
        MinecraftServer server = source.getServer();
        return PartyData.get(server).members(PartyData.get(server).partyOf(who));
    }

    /** The board of this person's party, or empty before they know who they are. */
    private static Optional<PartyBoard> partyBoardOf(Person person) {
        AgentId who = person.getAgentId();
        if (who == null || !(person.level() instanceof ServerLevel level)) {
            return Optional.empty();
        }
        MinecraftServer server = level.getServer();
        return Optional.of(PartyBoards.of(server, PartyData.get(server).partyOf(who)));
    }

    /**
     * Dumps every ledger row of every clearing project on the resolved Person's party board.
     *
     * <p>One line per target, machine-shaped ({@code TARGET x y z STATE}), because the question is
     * asked of the WORLD and not of the project: a project reports a tree cleared when the errand
     * for it ended, and only the blocks can settle whether anything is still standing there.
     */
    private static int boardTargets(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        Person person = resolve(ctx);
        if (person == null) return 0;
        Optional<PartyBoard> board = partyBoardOf(person);
        if (board.isEmpty()) {
            Replies.fail(source, Component.translatable(
                    "autarkia.command.no_identity", person.getName()));
            return 0;
        }
        int rows = 0;
        for (dev.luizloyola.autarkia.core.board.Project project : board.get().projects()) {
            if (!(project instanceof ClearArea area)) {
                continue;
            }
            for (ClearArea.Target target : area.ledger().values()) {
                Pos at = target.anchor();
                String line = "TARGET " + at.x() + " " + at.y() + " " + at.z() + " " + target.state();
                Replies.send(source, () -> Component.literal(line).withStyle(ChatFormatting.GRAY));
                rows++;
            }
        }
        int total = rows;
        Replies.send(source, () -> Component.literal("TARGETS " + total)
                .withStyle(ChatFormatting.LIGHT_PURPLE));
        return total;
    }

    /**
     * Toggles the clearing-project overlay for the calling player, or reports it when asked bare —
     * reading back rather than blind-toggling, the rule every switch in this tree follows.
     */
    private static int boardView(CommandSourceStack source, @Nullable Boolean on) {
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            Replies.fail(source, Component.literal(
                    "The board view is drawn for a PLAYER — run it as one."));
            return 0;
        }
        MinecraftServer server = source.getServer();
        if (on == null) {
            boolean watching = BoardViewer.isWatching(server, player);
            Replies.send(source, () -> Component.literal("The board view is "
                            + (watching ? "on." : "off."))
                    .withStyle(ChatFormatting.GRAY));
            return watching ? 1 : 0;
        }
        boolean now = BoardViewer.toggle(server, player, on);
        int projects = now ? BoardViewer.inRange(server, player) : 0;
        Replies.send(source, () -> Component.literal(now
                        ? "Board view on — " + (projects == 0
                                ? "no project within " + 512 + " blocks of you yet."
                                : projects + " project" + (projects == 1 ? "" : "s") + " in range. "
                                        + "Grey slice = unwalked, amber = being walked, green = "
                                        + "reported. White tree = pending, cyan = somebody is on "
                                        + "it, orange = cooling off, dim green = cleared, RED = "
                                        + "given up on.")
                        : "Board view off.")
                .withStyle(now ? ChatFormatting.AQUA : ChatFormatting.GRAY));
        return now ? 1 : 0;
    }

    /**
     * Posts "clear this area" to the resolved Person's party board.
     *
     * <p>The two corners mark the EDGES and that is the entire brief (decision: Luiz): no list of
     * trees, no seeded ledger — nobody knows what is inside a box until somebody walks it, which
     * is why the project offers slices to sweep alongside whatever it has found so far.
     *
     * <p>The box is three-dimensional as typed and the reply says so in blocks: a flat one reads
     * {@code ×1} and finds nothing, which has to be visible.
     */
    /** One corner argument, loaded — the same read every leaf of {@code post clear} makes. */
    private static BlockPos corner(com.mojang.brigadier.context.CommandContext<CommandSourceStack> ctx,
            String name) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        return BlockPosArgument.getLoadedBlockPos(ctx, name);
    }

    private static int boardPostClear(CommandContext<CommandSourceStack> ctx, BlockPos from, BlockPos to,
                                      double priority, @Nullable BlockPos yard) {
        CommandSourceStack source = ctx.getSource();
        Person person = resolve(ctx);
        if (person == null) return 0;
        AgentId who = person.getAgentId();
        if (who == null || !(person.level() instanceof ServerLevel level)) {
            Replies.fail(source, Component.translatable(
                    "autarkia.command.no_identity", person.getName()));
            return 0;
        }
        Region bounds = new Region(
                new Pos(Math.min(from.getX(), to.getX()), Math.min(from.getY(), to.getY()),
                        Math.min(from.getZ(), to.getZ())),
                new Pos(Math.max(from.getX(), to.getX()), Math.max(from.getY(), to.getY()),
                        Math.max(from.getZ(), to.getZ())));
        int wide = bounds.max().x() - bounds.min().x() + 1;
        int deep = bounds.max().z() - bounds.min().z() + 1;
        if (wide > CLEAR_MAX_SIDE || deep > CLEAR_MAX_SIDE) {
            // Refused rather than clamped: a box quietly shrunk is a box whose edges are not where
            // the operator put them, and every slice index after it names different ground.
            Replies.fail(source, Component.translatable("autarkia.command.clear.too_big",
                    wide, deep, CLEAR_MAX_SIDE));
            return 0;
        }
        MinecraftServer server = level.getServer();
        PartyId party = PartyData.get(server).partyOf(who);
        PartyBoard board = PartyBoards.of(server, party);
        // Trees, because they are the only thing anything knows how to clear. The kind becomes an
        // argument the day a second Clearing is registered; until then a choice of one is noise.
        ClearArea project = new ClearArea(TreeClearing.INSTANCE, bounds, priority,
                yard == null ? null : new Pos(yard.getX(), yard.getY(), yard.getZ()));
        int handle = board.post(project);
        PartyBoards.touch(server);
        OpJournal.record(source, PartyData.get(server).members(party),
                "posted #" + handle + " " + project.describe());
        // LOGGED: this creates durable, shared, persisted state that outlives everyone who works
        // it — the same reason cancel below is logged.
        Replies.send(source, () -> Component.translatable("autarkia.command.clear.posted",
                        handle, person.getName(), project.describe(), project.slices().size())
                .withStyle(ChatFormatting.LIGHT_PURPLE), true);
        return 1;
    }

    /**
     * Posts "get this many of this item into that yard" to the resolved Person's party board.
     *
     * <p>{@code at} is mandatory, unlike {@code post clear}'s optional yard: a clearing's box knows
     * it is done from its own ledger, but a gather's completion rule IS "the yard holds enough" —
     * with no yard there is nothing to ever check.
     *
     * <p>Refused before anything is posted when nothing registered here can ever make the item —
     * otherwise the settlement looks busy on a job that can never complete.
     */
    private static int boardPostGather(CommandContext<CommandSourceStack> ctx, ItemInput item, int count,
                                       BlockPos yard, double priority) {
        CommandSourceStack source = ctx.getSource();
        Person person = resolve(ctx);
        if (person == null) return 0;
        AgentId who = person.getAgentId();
        if (who == null || !(person.level() instanceof ServerLevel level)) {
            Replies.fail(source, Component.translatable(
                    "autarkia.command.no_identity", person.getName()));
            return 0;
        }
        // Through the compat template rather than ItemInput's own accessors, which changed shape
        // across targets — the same seam brainObtainItem crosses on.
        String id;
        try {
            id = ItemStacks.templateOf(item, source.registryAccess()).id();
        } catch (CommandSyntaxException invalid) {
            Replies.fail(source, Component.translatable("autarkia.command.obtain.not_an_item"));
            return 0;
        }
        // By CONTENT (knowsAnyOf), not by the literal spec's own identity: the registered producer
        // is keyed on a mod-declared spec like Stock.LOGS, never on this command's freshly built
        // per-item one, so asking whether IT is a registered key would always say no.
        if (!Producers.knowsAnyOf(Set.of(id))) {
            Replies.fail(source, Component.translatable("autarkia.command.gather.cannot_make", id));
            return 0;
        }
        MinecraftServer server = level.getServer();
        PartyId party = PartyData.get(server).partyOf(who);
        PartyBoard board = PartyBoards.of(server, party);
        Gather project = new Gather(ItemSpec.anyOf(Set.of(id)), count,
                new Pos(yard.getX(), yard.getY(), yard.getZ()), priority, party, CarrySplit.INSTANCE);
        int handle = board.post(project);
        PartyBoards.touch(server);
        OpJournal.record(source, PartyData.get(server).members(party),
                "posted #" + handle + " " + project.describe());
        // LOGGED: the same reason clear's post is — a posted project is durable, shared,
        // persisted state that outlives everyone who works it.
        Replies.send(source, () -> Component.translatable("autarkia.command.gather.posted",
                        handle, person.getName(), project.describe())
                .withStyle(ChatFormatting.LIGHT_PURPLE), true);
        return 1;
    }

    /**
     * Cancels a project by the handle the readout shows, on the board the caller NAMED —
     * {@code board personal cancel <n>} or {@code board party cancel <n>}. There is no bare form:
     * both boards number their own projects from one, so both readouts show a {@code #1} and a
     * default would be a coin toss dressed as an answer.
     *
     * <p>A cancel that tried one board and fell back to the other, asked to drop a party's
     * clearing, silently dropped the caller's own standing want instead and reported success
     * (live, 2026-08-10).
     *
     * <p>Any member may cancel a shared project — the dev answer, until layer 4 has opinions about
     * who decides.
     */
    private static int boardCancel(CommandContext<CommandSourceStack> ctx, int handle, boolean party) {
        CommandSourceStack source = ctx.getSource();
        Person person = resolve(ctx);
        if (person == null) return 0;
        var cancelled = party
                ? partyBoardOf(person).flatMap(board -> board.cancel(handle))
                : person.board().cancel(handle);
        if (party && cancelled.isPresent() && person.level() instanceof ServerLevel level) {
            PartyBoards.touch(level.getServer());
        }
        if (cancelled.isEmpty()) {
            Replies.fail(source, Component.translatable(party
                            ? "autarkia.command.board.no_project_party"
                            : "autarkia.command.board.no_project_personal",
                    handle, person.getName()));
            return 0;
        }
        String what = cancelled.get().describe();
        // The board it acted on decides the reach: a shared project vanishing is every member's
        // explanation for the work that stopped, a personal one is only its owner's.
        String dropped = "cancelled #" + handle + " " + what;
        if (party) {
            OpJournal.record(source, partyMembers(source, person), dropped);
        } else {
            OpJournal.record(source, person.getAgentId(), dropped);
        }
        // LOGGED as well as journalled: this destroys layer-3 state, and the op lines above are
        // per-agent files. Left invisible in both, a cancel reads as the board simply saying
        // "nothing posted". Caught live, chasing a Person whose want had evaporated.
        Replies.send(source, () -> Component.translatable(party
                                ? "autarkia.command.board.dropped_party"
                                : "autarkia.command.board.dropped_personal",
                        person.getName(), handle, what)
                .withStyle(ChatFormatting.LIGHT_PURPLE), true);
        return 1;
    }

    /** Runs {@link FellTree} on the resolved Person's nearest remembered tree — the staging
     *  path, and the operator's shortcut past perception. */
    private static int brainChop(CommandContext<CommandSourceStack> ctx, @Nullable BlockPos at) {
        CommandSourceStack source = ctx.getSource();
        Person person = resolve(ctx);
        if (person == null) return 0;
        Pos anchor;
        if (at != null) {
            // Explicit coordinates: the operator's shortcut past perception — the grind
            // harness's staging path, and a way to point a Person at any specific tree.
            anchor = new Pos(at.getX(), at.getY(), at.getZ());
        } else {
            Pos feet = new Pos(person.blockPosition().getX(), person.blockPosition().getY(),
                    person.blockPosition().getZ());
            var memory = Knowledges.of(source.getServer()).forPerson(person.agentId())
                    .nearest(Pois.TREE, feet);
            if (memory.isEmpty()) {
                Replies.fail(source, Component.translatable("autarkia.command.chop.no_tree",
                        person.getName()));
                return 0;
            }
            anchor = memory.get().anchor();
        }
        boolean autoDisabled = person.brain().run(new FellTree(anchor));
        Component suffix = AgentCommands.autoDisabledNote(autoDisabled);
        OpJournal.record(source, person.getAgentId(), "told to chop the tree at ("
                + anchor.x() + ", " + anchor.y() + ", " + anchor.z() + ")"
                + (autoDisabled ? ", autonomy off" : ""));
        Replies.send(source, () -> Component.translatable("anima.command.state",
                person.getName(), person.brain().describe())
                .append(suffix).withStyle(ChatFormatting.AQUA));
        return 1;
    }

    /** Runs {@link ObtainItem} (logs × count): rounds of scavenge-or-chop until the pack holds the
     *  quota, run to completion in one invocation. */
    private static int brainObtain(CommandContext<CommandSourceStack> ctx, int count) {
        CommandSourceStack source = ctx.getSource();
        Person person = resolve(ctx);
        if (person == null) return 0;
        boolean autoDisabled = person.brain().run(new ObtainItem(Stock.LOGS, count));
        Component suffix = AgentCommands.autoDisabledNote(autoDisabled);
        OpJournal.record(source, person.getAgentId(), "told to obtain " + count + " logs"
                + (autoDisabled ? ", autonomy off" : ""));
        Replies.send(source, () -> Component.translatable("anima.command.state",
                person.getName(), person.brain().describe())
                .append(suffix).withStyle(ChatFormatting.AQUA));
        return 1;
    }

    /**
     * {@link ObtainItem} for one concrete item — scavenge, produce or CRAFT, whatever prices
     * cheapest. The spec is {@link dev.luizloyola.anima.core.inv.ItemSpec#anyOf}, so the order
     * persists like any other plan and two sessions asking for the same item mean the same spec.
     */
    private static int brainObtainItem(CommandContext<CommandSourceStack> ctx,
                                       net.minecraft.commands.arguments.item.ItemInput item,
                                       int count) {
        CommandSourceStack source = ctx.getSource();
        Person person = resolve(ctx);
        if (person == null) return 0;
        // Through the compat template rather than ItemInput's own accessors, which changed shape
        // across targets — the same seam `inv give` already crosses on.
        String id;
        try {
            id = dev.luizloyola.anima.compat.inv.ItemStacks
                    .templateOf(item, source.registryAccess()).id();
        } catch (com.mojang.brigadier.exceptions.CommandSyntaxException invalid) {
            Replies.fail(source, Component.translatable("autarkia.command.obtain.not_an_item"));
            return 0;
        }
        boolean autoDisabled = person.brain().run(new ObtainItem(
                dev.luizloyola.anima.core.inv.ItemSpec.anyOf(java.util.Set.of(id)), count));
        Component suffix = AgentCommands.autoDisabledNote(autoDisabled);
        OpJournal.record(source, person.getAgentId(), "told to obtain " + count + " " + id
                + (autoDisabled ? ", autonomy off" : ""));
        Replies.send(source, () -> Component.translatable("anima.command.state",
                person.getName(), person.brain().describe())
                .append(suffix).withStyle(ChatFormatting.AQUA));
        return 1;
    }

    /**
     * Toggles the {@link TreeSplitViewer} survey around the calling player, or retunes its
     * radius while it is on. {@code radius} 0 means "no radius given" — plain toggle.
     */
    private static int treeView(CommandSourceStack source, int radius)
            throws CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        int active = TreeSplitViewer.toggle(source.getServer(), player, radius);
        Replies.send(source, () -> Component.literal(active > 0
                ? "Surveying the trees within " + active + " blocks — every tree its own colour."
                : "The tree survey is off.").withStyle(ChatFormatting.GRAY));
        return 1;
    }

    /**
     * What mind a freshly spawned Person starts with — the only thing the three {@code spawn}
     * literals differ by.
     */
    private enum Mind {
        /** The arbiter deciding everything. */
        FULL,
        /** Autonomy off: an inert body that acts only when a {@code /autarkia brain} order says so. */
        NO_BRAIN,
        /** Thinking normally, minus the idle drift — see {@code /autarkia brain wander}. */
        NO_WANDER
    }

    /**
     * Attaches the optional {@code [<pos>] [name]} leaves to a spawn literal, each executor
     * spawning with the given {@code mind}, so the three literals share one argument shape.
     *
     * <p>The name is a NON-greedy string on purpose: for real coords like {@code 10 -59 5} a
     * one-word name would consume only {@code 10} and leave {@code -59 5} unparsed, so that branch
     * loses and the fully-consuming Vec3 branch wins. Multi-word names must be quoted
     * ({@code "Alice Smith"}) — a greedy name would swallow the whole line, coordinates and all.
     */
    private static LiteralArgumentBuilder<CommandSourceStack> spawnLeaves(
            LiteralArgumentBuilder<CommandSourceStack> node, Mind mind) {
        return node
                .executes(ctx -> personSpawn(ctx.getSource(), null, null, mind))
                .then(Commands.argument("pos", Vec3Argument.vec3())
                        .executes(ctx -> personSpawn(ctx.getSource(),
                                Vec3Argument.getVec3(ctx, "pos"), null, mind))
                        .then(Commands.argument("name", StringArgumentType.string())
                                .executes(ctx -> personSpawn(ctx.getSource(),
                                        Vec3Argument.getVec3(ctx, "pos"),
                                        StringArgumentType.getString(ctx, "name"), mind))))
                .then(Commands.argument("name", StringArgumentType.string())
                        .executes(ctx -> personSpawn(ctx.getSource(), null,
                                StringArgumentType.getString(ctx, "name"), mind)));
    }

    /**
     * Spawns a new Person at {@code pos} (or the source's position when {@code null}), facing
     * south (yaw 0) like {@code /summon}. Directory-first: an identity is registered in the
     * {@link PersonDirectory} ({@code name} if given, else generated), and linked to the entity
     * <em>before</em> it enters the world. (A plain {@code /summon autarkia:person} mints its
     * generated identity a tick later instead, in {@link Person#tick()}.) The entity is created
     * before the directory is touched, so a null entity leaves no orphan entry.
     *
     * <p>{@code mind} is applied before the first tick, so the Person is never briefly something
     * else: {@link Mind#NO_BRAIN} inert but still drivable, {@link Mind#NO_WANDER} thinking
     * normally without the idle drift.
     */
    private static int personSpawn(CommandSourceStack source, @Nullable Vec3 pos, @Nullable String name,
                                   Mind mind) {
        String trimmed = name == null ? null : name.trim();
        if (trimmed != null && trimmed.isEmpty()) {
            Replies.fail(source, Component.translatable("autarkia.command.spawn.blank_name"));
            return 0;
        }
        ServerLevel level = source.getLevel();
        Person person = ModEntities.PERSON.create(level, EntitySpawnReason.COMMAND);
        if (person == null) {
            Replies.fail(source, Component.translatable("autarkia.command.spawn.no_entity"));
            return 0;
        }
        PersonDirectory directory = PersonDirectory.get(source.getServer());
        PersonIdentity identity = trimmed == null ? directory.createPerson() : directory.createPerson(trimmed);
        Vec3 spawnPos = pos != null ? pos : source.getPosition();
        // Yaw 0 = facing south, matching /summon (which keeps the entity's own default rotation);
        // pitch pinned flat since a Person stands upright (pitch is render-only head tilt, see face()).
        person.snapTo(spawnPos.x, spawnPos.y, spawnPos.z, 0.0F, 0.0F);
        person.assignPerson(identity.id());
        // Both overrides land before the entity's first serverAiStep, so it spawns as asked
        // rather than deciding for itself on tick one: "no brain" drops the arbiter into manual
        // mode, "no wander" leaves it running and mutes the drive that would carry them off.
        switch (mind) {
            case NO_BRAIN -> person.brain().setAuto(false);
            case NO_WANDER -> person.brain().setWander(false);
            case FULL -> { }
        }
        if (!level.addFreshEntity(person)) {
            Replies.fail(source, Component.translatable("autarkia.command.spawn.not_added"));
            return 0;
        }
        Appearance appearance = identity.appearance();
        String where = String.format(Locale.ROOT, "%.1f %.1f %.1f", spawnPos.x, spawnPos.y, spawnPos.z);
        OpJournal.record(source, identity.id(), "spawned at " + where + switch (mind) {
            case FULL -> "";
            case NO_BRAIN -> ", brain off";
            case NO_WANDER -> ", wander muted";
        });
        Component brainNote = switch (mind) {
            case FULL -> Component.empty();
            case NO_BRAIN -> Component.translatable("autarkia.command.spawn.brain_off");
            case NO_WANDER -> Component.translatable("autarkia.command.spawn.wander_muted");
        };
        Replies.send(source, () -> Component.translatable("autarkia.command.spawn.spawned")
                .append(Component.literal(identity.name()).withStyle(ChatFormatting.AQUA))
                .append(Component.translatable("autarkia.command.spawn.at",
                                Component.translatable(appearance.gender().nameKey()), where)
                        .append(brainNote)
                        .withStyle(ChatFormatting.GRAY)), true);
        return 1;
    }


    /**
     * Unmakes one Person by explicit name or id: every store registered with {@code AgentRecords}
     * drops what it holds for them.
     *
     * <p>Unlike the {@code purge graveyard} it replaces, it never infers who is gone (an ambiguous
     * name fails rather than guessing) and it asks the registry rather than enumerating stores —
     * the old command knew about three stores out of four and left 722 orphan party rows behind.
     *
     * <p><b>Refuses while the body is loaded</b>, which would otherwise mint a fresh anonymous
     * identity on its next tick.
     */
    /**
     * Erases the SUBJECT. The token-matching this used to carry is {@code AgentLookup}'s now, in
     * Anima, so this and {@code as} agree on what a name means — and being subject-scoped, it can
     * only be reached by naming somebody.
     */
    private static int personErase(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        MinecraftServer server = source.getServer();
        AgentId id = Subject.id(ctx);
        if (id == null) return 0; // already reported
        PersonIdentity identity = PersonDirectory.get(server).all().stream()
                .filter(i -> i.id().equals(id)).findFirst().orElse(null);
        if (identity == null) {
            Replies.fail(source, Component.translatable("autarkia.command.erase.not_a_person",
                    AgentCommands.label(server, id)));
            return 0;
        }
        if (findLoaded(server, id) != null) {
            Replies.fail(source, Component.translatable("autarkia.command.erase.loaded",
                    identity.name()));
            return 0;
        }

        // Before the wipe, not after: erase drops the ring, but the file sink has already taken
        // this line, so the durable journal ends saying why it stops rather than just stopping.
        OpJournal.record(source, id, "erased");
        List<String> touched = AgentRecords.erase(server, id);
        // LOGGED, like the spawn it undoes, and for the same reason the old command was: erasing
        // destroys the record that would have said what happened to it.
        Replies.send(source, () -> (touched.isEmpty()
                        ? Component.translatable("autarkia.command.erase.erased_nothing",
                                identity.name(), AgentCommands.shortId(id))
                        : Component.translatable("autarkia.command.erase.erased",
                                identity.name(), AgentCommands.shortId(id),
                                String.join(", ", touched)))
                .withStyle(ChatFormatting.GRAY), true);
        return 1;
    }



    private static Person nearest(CommandSourceStack source) {
        ServerLevel level = source.getLevel();
        Vec3 origin = source.getPosition();
        AABB box = AABB.ofSize(origin, NEAREST_RADIUS * 2, NEAREST_RADIUS * 2, NEAREST_RADIUS * 2);
        Person nearest = level.getEntitiesOfClass(Person.class, box, Person::isAlive).stream()
                .min((a, b) -> Double.compare(a.distanceToSqr(origin), b.distanceToSqr(origin)))
                .orElse(null);
        if (nearest == null) {
            Replies.fail(source, Component.translatable("autarkia.command.no_person_near",
                    (int) NEAREST_RADIUS));
        }
        return nearest;
    }

    /** {@link #resolveBody} narrowed to a Person, for the commands that are about being one. */
    private static @Nullable Person resolve(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        AgentBody body = Subject.body(ctx);
        if (body == null) {
            return null;
        }
        if (body instanceof Person person) {
            return person;
        }
        Replies.fail(source, Component.translatable("autarkia.command.not_a_person",
                body.entity().getName()));
        return null;
    }


    /** Every live Person across every dimension. {@code getEntities} still hands back a Person killed
     *  moments ago (it lingers through its death animation before being swept), so {@code isAlive}
     *  is filtered on, or {@code list} and the resolver would act on a corpse. */
    private static List<Person> loadedPersons(MinecraftServer server) {
        return Persons.loaded(server);
    }

    /** The live Person with this id, searching every dimension, or {@code null} if none is loaded.
     *  A dead/dying Person (not yet swept) does not count — see {@link Persons#findLoaded}. */
    private static @Nullable Person findLoaded(MinecraftServer server, AgentId id) {
        return Persons.findLoaded(server, id);
    }

    // --- contacts: who has been introduced to whom ----------------------------------------------

    private static int whoisResolved(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        Person target = resolve(ctx);
        if (target == null) return 0;
        report(source, target);
        return 1;
    }

    private static int whoisTargets(CommandSourceStack source,
                                    java.util.Collection<? extends Entity> targets) {
        List<Person> persons = targets.stream().filter(e -> e instanceof Person).map(e -> (Person) e).toList();
        if (persons.isEmpty()) {
            Replies.fail(source, Component.translatable("autarkia.command.whois.no_person"));
            return 0;
        }
        persons.forEach(person -> report(source, person));
        return persons.size();
    }

    private static void report(CommandSourceStack source, Person person) {
        AgentId id = person.agentId();
        if (id == null) {
            Replies.send(source, () -> Component.translatable("autarkia.command.whois.spawning")
                    .withStyle(ChatFormatting.GRAY));
            return;
        }
        PersonIdentity identity = PersonDirectory.get(source.getServer()).find(id).orElse(null);
        if (identity == null) {
            Replies.send(source, () -> Component.translatable("autarkia.command.whois.unknown", id)
                    .withStyle(ChatFormatting.GRAY));
            return;
        }
        Appearance appearance = identity.appearance();
        // Full tier (name) + external tier (gender, skin) + the stable id.
        ChatFormatting genderColor = appearance.gender().choose(ChatFormatting.BLUE, ChatFormatting.LIGHT_PURPLE);
        MutableComponent line = Component.literal(identity.name()).withStyle(ChatFormatting.AQUA)
                .append(Component.literal(" ")
                        .append(Component.translatable(appearance.gender().nameKey()))
                        .withStyle(genderColor))
                .append(Component.literal(" " + appearance.model()).withStyle(ChatFormatting.DARK_AQUA))
                .append(Component.literal("  " + appearance.look().describe()).withStyle(ChatFormatting.GRAY))
                .append(Component.literal("  " + id).withStyle(ChatFormatting.DARK_GRAY));
        Replies.send(source, () -> line);
    }
}
