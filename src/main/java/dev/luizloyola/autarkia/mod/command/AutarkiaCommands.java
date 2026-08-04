package dev.luizloyola.autarkia.mod.command;

import java.util.Map;
import dev.luizloyola.anima.mod.identity.AgentDirectory;
import dev.luizloyola.anima.mod.identity.AgentRecords;
import dev.luizloyola.anima.core.agent.PrivateIdentity;
import dev.luizloyola.anima.mod.body.AgentBodies;
import dev.luizloyola.anima.mod.body.AgentBody;
import dev.luizloyola.anima.mod.command.AgentCommands;
import dev.luizloyola.anima.mod.command.AgentSelection;
import dev.luizloyola.anima.mod.command.Replies;
import com.mojang.brigadier.arguments.FloatArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
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
import dev.luizloyola.anima.core.brain.task.SatisfyHunger;
import dev.luizloyola.anima.core.config.ConfigValues;
import dev.luizloyola.anima.mod.command.ConfigCommands;
import dev.luizloyola.anima.mod.config.ConfigFile;
import dev.luizloyola.autarkia.core.config.AutarkiaConfig;
import dev.luizloyola.anima.core.config.Config;
import dev.luizloyola.anima.core.config.Knob;
import dev.luizloyola.anima.core.inv.ArmorType;
import dev.luizloyola.anima.core.inv.Inventory;
import dev.luizloyola.anima.core.inv.ItemSpec;
import dev.luizloyola.autarkia.core.board.Stock;
import dev.luizloyola.autarkia.core.tree.ChopPlannedTree;
import dev.luizloyola.autarkia.core.tree.Pois;
import dev.luizloyola.anima.core.brain.sense.Pos;
import dev.luizloyola.anima.core.log.Category;
import dev.luizloyola.anima.core.log.Entry;
import dev.luizloyola.autarkia.core.person.Appearance;
import dev.luizloyola.anima.core.agent.Needs;
import dev.luizloyola.anima.core.agent.AgentId;
import dev.luizloyola.autarkia.core.person.PersonIdentity;
import dev.luizloyola.anima.mod.brain.KnowledgeViewer;
import dev.luizloyola.anima.mod.brain.Knowledges;
import dev.luizloyola.anima.mod.brain.BeingViewer;
import dev.luizloyola.anima.mod.debug.DebugLayer;
import dev.luizloyola.anima.mod.debug.DebugView;
import dev.luizloyola.autarkia.mod.debug.TreeChopPlanViewer;
import dev.luizloyola.autarkia.mod.debug.TreeSplitViewer;
import dev.luizloyola.autarkia.mod.entity.ModEntities;
import dev.luizloyola.autarkia.mod.entity.Persons;
import dev.luizloyola.autarkia.mod.entity.Person;
import dev.luizloyola.anima.mod.log.ThoughtBroadcast;
import dev.luizloyola.anima.mod.net.ContactsSync;
import dev.luizloyola.autarkia.mod.person.PersonDirectory;
import dev.luizloyola.anima.mod.social.ContactData;
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
 * <em>as</em> (one line, every Person in turn), else the source's pinned Person, else the nearest.
 * Bare {@code select} pins the Person a player is looking at, unpins when looking at nobody, and
 * pins the nearest from the console; {@code list} exists because names are not unique. Pins live
 * in {@link AgentSelection} — in memory, per source, gone on restart.
 */
public final class AutarkiaCommands {
    private AutarkiaCommands() {}

    private static final double NEAREST_RADIUS = 32.0;

    public static void register(ConfigFile configFile) {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
                dispatcher.register(Commands.literal("autarkia")
                        // Pin the Person that this source's later commands target. "clear"/"show" are
                        // literals, so they win over a Person literally named clear/show — pin those by id.
                        .then(AgentCommands.select())
                        .then(Commands.literal("list")
                                .executes(ctx -> listPersons(ctx.getSource())))
                        .then(Commands.literal("whois")
                                .executes(ctx -> whoisResolved(ctx.getSource()))
                                .then(Commands.argument("targets", EntityArgument.entities())
                                        .executes(ctx -> whoisTargets(ctx.getSource(),
                                                EntityArgument.getEntities(ctx, "targets")))))
                        // Who knows whom. Until the encounter rung lands there is no in-world way
                        // to be introduced, so "meet" is the scaffold that stands in for it.
                        .then(AgentCommands.contacts())
                        // Who belongs with whom — layer 3's scope. join/leave are the dev
                        // stand-ins until the social era's group-up handshake exists.
                        .then(AgentCommands.party())
                        .then(AgentCommands.nav())
                        // nav (above) drives the legs directly — locomotion debug; brain runs
                        // tasks through the executor, the machinery the arbiter feeds. Anima's
                        // shared brain verbs, plus the one that is ours: obtain is a log quota,
                        // not the library's business.
                        .then(AgentCommands.brain()
                                // Fell the nearest remembered tree by walking its compiled dance
                                // card (ChopPlannedTree).
                                .then(Commands.literal("chop")
                                        .executes(ctx -> brainChop(ctx.getSource(), null))
                                        .then(Commands.argument("pos", BlockPosArgument.blockPos())
                                                .executes(ctx -> brainChop(ctx.getSource(),
                                                        BlockPosArgument.getBlockPos(
                                                                ctx, "pos")))))
                                .then(Commands.literal("obtain")
                                        .then(Commands.literal("logs")
                                                .executes(ctx -> brainObtain(ctx.getSource(), 16))
                                                .then(Commands.argument("count", IntegerArgumentType.integer(1))
                                                        .executes(ctx -> brainObtain(ctx.getSource(),
                                                                IntegerArgumentType.getInteger(ctx, "count")))))))
                        // Thinking out loud: forwards the resolved Person's `think` journal lines
                        // to chat (gray italics) until toggled off.
                        .then(AgentCommands.think())
                        // The per-person debug journal (see the log package). Top-level, not under a
                        // subsystem group, because one Person's log interleaves brain + pathfind + body.
                        .then(AgentCommands.log())
                        // What the resolved Person REMEMBERS (the knowledge store) — beliefs, not
                        // world state; "view" renders those beliefs as particles + discovery chat.
                        .then(AgentCommands.knowledge())
                        .then(AgentCommands.horizon())
                        .then(AgentCommands.survey())
                        // Who is holding what — site claims and item leases, one semantics, two
                        // keyspaces. The readout contention never had.
                        .then(AgentCommands.claims())
                        // The in-world debug view: gizmo lines, boxes and floating text over the
                        // SELECTED Person. Per-player, and the only way to raise several layers at
                        // once (the wand's shift-click cycles them one at a time).
                        .then(AgentCommands.debug())
                        // How the ground around you would carve into individual trees —
                        // TreeShape's split painted over the live world, no Person or perception
                        // involved. Ours alone, like board: Anima has no idea what a tree is.
                        .then(Commands.literal("tree")
                                .then(Commands.literal("view")
                                        .executes(ctx -> treeView(ctx.getSource(), 0))
                                        .then(Commands.argument("radius",
                                                        IntegerArgumentType.integer(4, 32))
                                                .executes(ctx -> treeView(ctx.getSource(),
                                                        IntegerArgumentType.getInteger(
                                                                ctx, "radius")))))
                                // The same trees with their ChopPlan dance cards — mast, dig
                                // tunnels, chop order, refusals — read by eye before any Person
                                // swings an axe.
                                .then(Commands.literal("plan")
                                        .executes(ctx -> treePlan(ctx.getSource(), 0))
                                        .then(Commands.argument("radius",
                                                        IntegerArgumentType.integer(4, 32))
                                                .executes(ctx -> treePlan(ctx.getSource(),
                                                        IntegerArgumentType.getInteger(
                                                                ctx, "radius"))))))
                        // What a PERSON is like, in our own file; /anima config holds the
                        // server-wide limits, the journal and the flee weights. Same subcommand,
                        // built for whichever set it is handed.
                        .then(ConfigCommands.tree(AutarkiaConfig.store(), configFile))
                        // Layer 3, both scopes at once: the personal board (what this body wants
                        // for itself) and the party board (what the group has posted).
                        .then(Commands.literal("board")
                                .executes(ctx -> boardShow(ctx.getSource()))
                                .then(Commands.literal("cancel")
                                        .then(Commands.argument("project", IntegerArgumentType.integer(1))
                                                .executes(ctx -> boardCancel(ctx.getSource(),
                                                        IntegerArgumentType.getInteger(ctx, "project"))))))
                        // Who they can currently SEE — the peers() sense: Persons and live
                        // players, one seamless list, activity read off the visible body.
                        .then(AgentCommands.peers())
                        // What this one is running: species -> modifiers -> effective.
                        .then(AgentCommands.profile())
                        .then(AgentCommands.inv(registryAccess))
                        // "person", not "brain": these are body readouts (vitals live with the
                        // entity); the brain group above holds the decision machinery.
                        .then(Commands.literal("person")
                                // "spawn" is autonomous, "nobrain" starts with autonomy off,
                                // "nowander" thinks normally but never drifts. All three take the
                                // same [<pos>] [name] leaves (see spawnLeaves); being literals, the
                                // children win over a Person named "nobrain" — quote it to use
                                // that as a name.
                                .then(spawnLeaves(Commands.literal("spawn"), Mind.FULL)
                                        .then(spawnLeaves(Commands.literal("nobrain"), Mind.NO_BRAIN))
                                        .then(spawnLeaves(Commands.literal("nowander"), Mind.NO_WANDER)))
                                // No `purge graveyard` here — REMOVED 2026-08-04. It called every
                                // identity with no LOADED entity "dead" and destroyed its
                                // directory entry, knowledge and journal, which deletes most of a
                                // settlement on any world where settlers leave render distance.
                                // Absence never meant death, and after the fact the two are
                                // indistinguishable: burial is recorded at die() or not at all
                                // (2026-08-03-persistence-design.md). `erase` replaces it, taking
                                // an explicit id.
                                .then(Commands.literal("erase")
                                        .then(Commands.argument("who", StringArgumentType.word())
                                                .suggests(ERASABLE_SUGGESTIONS)
                                                .executes(ctx -> personErase(ctx.getSource(),
                                                        StringArgumentType.getString(ctx, "who")))))
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

    /**
     * Prints both boards the resolved Person can reach (their own and their party's) one row per
     * project, with its handle, its state and how many of its items are claimed.
     *
     * <p>Both, because a summary could not tell a quiet personal board from an empty party one.
     */
    private static int boardShow(CommandSourceStack source) {
        Person person = resolve(source);
        if (person == null) return 0;
        Replies.send(source, () -> Component.literal(person.getName().getString() + " boards:")
                .withStyle(ChatFormatting.LIGHT_PURPLE));
        for (String line : person.brain().describeBoard()) {
            Replies.send(source, () -> Component.literal("  " + line)
                    .withStyle(ChatFormatting.LIGHT_PURPLE));
        }
        return 1;
    }

    /**
     * Cancels a project by the handle the readout shows. Scoped to the personal board: nothing can
     * be posted to a party board yet, and cancelling a shared project will be a different question
     * from dropping one's own standing want.
     */
    private static int boardCancel(CommandSourceStack source, int handle) {
        Person person = resolve(source);
        if (person == null) return 0;
        var cancelled = person.board().cancel(handle);
        if (cancelled.isEmpty()) {
            Replies.fail(source, Component.literal(
                    "No project #" + handle + " on " + person.getName().getString() + "'s own board."));
            return 0;
        }
        // LOGGED: this destroys layer-3 state. No journal line records it — the journal belongs
        // to the agent, and an agent does not narrate what was done TO it — so left unlogged a
        // cancel is invisible everywhere, the board reading "nothing posted" afterwards.
        // Caught live, chasing a Person whose want had evaporated.
        Replies.send(source, () -> Component.literal(person.getName().getString()
                        + " drops #" + handle + " — " + cancelled.get().describe())
                .withStyle(ChatFormatting.LIGHT_PURPLE), true);
        return 1;
    }

    /**
     * Runs {@link ChopPlannedTree} on the resolved Person's nearest remembered tree — the
     * dance-card executor, ordered directly for staging.
     */
    private static int brainChop(CommandSourceStack source, @Nullable BlockPos at) {
        Person person = resolve(source);
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
                Replies.fail(source, Component.literal(
                        person.getName().getString() + " knows no tree to fell."));
                return 0;
            }
            anchor = memory.get().anchor();
        }
        boolean autoDisabled = person.brain().run(new ChopPlannedTree(anchor));
        String suffix = AgentCommands.autoDisabledSuffix(autoDisabled);
        Replies.send(source, () -> Component.literal(person.getName().getString() + ": "
                + person.brain().describe() + suffix).withStyle(ChatFormatting.AQUA));
        return 1;
    }

    /** Runs {@link ObtainItem} (logs × count): rounds of scavenge-or-chop until the pack holds the
     *  quota, run to completion in one invocation. */
    private static int brainObtain(CommandSourceStack source, int count) {
        Person person = resolve(source);
        if (person == null) return 0;
        boolean autoDisabled = person.brain().run(new ObtainItem(Stock.LOGS, count));
        String suffix = AgentCommands.autoDisabledSuffix(autoDisabled);
        Replies.send(source, () -> Component.literal(person.getName().getString() + ": "
                + person.brain().describe() + suffix).withStyle(ChatFormatting.AQUA));
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
     * Toggles the {@link TreeChopPlanViewer} around the calling player, or retunes its radius
     * while it is on — the same lifecycle as {@link #treeView}, painting dance cards instead of
     * ownership.
     */
    private static int treePlan(CommandSourceStack source, int radius)
            throws CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        int active = TreeChopPlanViewer.toggle(source.getServer(), player, radius);
        Replies.send(source, () -> Component.literal(active > 0
                ? "Planning the chop of every tree within " + active
                        + " blocks — green swings first, red last, refusals loud."
                : "The chop plan view is off.").withStyle(ChatFormatting.GRAY));
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
            Replies.fail(source, Component.literal("Name must not be blank."));
            return 0;
        }
        ServerLevel level = source.getLevel();
        Person person = ModEntities.PERSON.create(level, EntitySpawnReason.COMMAND);
        if (person == null) {
            Replies.fail(source, Component.literal("Could not create the Person entity."));
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
            Replies.fail(source, Component.literal("Could not add the Person to the world."));
            return 0;
        }
        Appearance appearance = identity.appearance();
        String where = String.format(Locale.ROOT, "%.1f %.1f %.1f", spawnPos.x, spawnPos.y, spawnPos.z);
        String brainNote = switch (mind) {
            case FULL -> "";
            case NO_BRAIN -> " — brain off (/autarkia brain auto true to enable)";
            case NO_WANDER -> " — wander muted (/autarkia brain wander true to enable)";
        };
        Replies.send(source, () -> Component.literal("Spawned ")
                .append(Component.literal(identity.name()).withStyle(ChatFormatting.AQUA))
                .append(Component.literal(" (" + appearance.gender() + ") at " + where + brainNote)
                        .withStyle(ChatFormatting.GRAY)), true);
        return 1;
    }

    /** Every identity the directory holds, by name and by short id — what {@code erase} accepts.
     *  Directory-backed rather than body-backed on purpose: the whole point of erase is to reach a
     *  record whose entity is not around, which is most of them. */
    private static final SuggestionProvider<CommandSourceStack> ERASABLE_SUGGESTIONS = (ctx, builder) -> {
        Stream<String> tokens = PersonDirectory.get(ctx.getSource().getServer()).all().stream()
                .flatMap(identity -> Stream.of(
                        identity.name().contains(" ") ? '"' + identity.name() + '"' : identity.name(),
                        AgentCommands.shortId(identity.id())));
        return SharedSuggestionProvider.suggest(tokens, builder);
    };

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
    private static int personErase(CommandSourceStack source, String rawToken) {
        MinecraftServer server = source.getServer();
        PersonDirectory directory = PersonDirectory.get(server);
        String token = rawToken.trim();
        String lower = token.toLowerCase(Locale.ROOT);

        // Id (or short-id prefix) first: it is unambiguous, and names are not unique.
        List<PersonIdentity> matches = directory.all().stream()
                .filter(i -> i.id().toString().toLowerCase(Locale.ROOT).startsWith(lower))
                .toList();
        if (matches.isEmpty()) {
            matches = directory.all().stream()
                    .filter(i -> i.name().equalsIgnoreCase(token))
                    .toList();
        }
        if (matches.isEmpty()) {
            Replies.fail(source, Component.literal(
                    "No identity matches '" + token + "' — try a name or id from the list command."));
            return 0;
        }
        if (matches.size() > 1) {
            String ids = matches.stream().map(i -> AgentCommands.shortId(i.id()))
                    .collect(Collectors.joining(", "));
            Replies.fail(source, Component.literal(matches.size() + " identities named '" + token
                    + "' — pick one by id: " + ids));
            return 0;
        }

        PersonIdentity identity = matches.get(0);
        AgentId id = identity.id();
        if (findLoaded(server, id) != null) {
            Replies.fail(source, Component.literal(identity.name() + " is loaded — kill or remove "
                    + "the body first, or it will mint a fresh identity on its next tick."));
            return 0;
        }

        List<String> touched = AgentRecords.erase(server, id);
        // LOGGED, like the spawn it undoes, and for the same reason the old command was: erasing
        // destroys the record that would have said what happened to it.
        Replies.send(source, () -> Component.literal("Erased " + identity.name() + " ("
                + AgentCommands.shortId(id) + ") from "
                + (touched.isEmpty() ? "nothing — no store held anything"
                        : String.join(", ", touched))
                + ".").withStyle(ChatFormatting.GRAY), true);
        return 1;
    }

    /** Prints the resolved Person's need levels — the {@code needs().describe()} one-liner. */
    private static int personNeeds(CommandSourceStack source) {
        Person person = resolve(source);
        if (person == null) return 0;
        Replies.send(source, () -> Component.literal(person.getName().getString() + ": "
                + person.needs().describe()).withStyle(ChatFormatting.AQUA));
        return 1;
    }

    /**
     * Sets the resolved Person's food level (0..20) and saturation (0.0 when omitted) and echoes the
     * readout — the dev knob for exercising starvation, regen and the Eat instinct without waiting
     * out the burn. Food goes first because saturation clamps against it; exhaustion is zeroed so
     * what follows is deterministic.
     */
    private static int personSetFood(CommandSourceStack source, int food, float saturation) {
        Person person = resolve(source);
        if (person == null) return 0;
        Needs needs = person.needs();
        needs.setFoodLevel(food);
        needs.setSaturation(saturation);
        needs.setExhaustion(0.0F);
        // LOGGED: needs persist on the entity and drive the arbiter — a hand-set hunger explains
        // an eat that would otherwise read as the brain deciding something inexplicable.
        Replies.send(source, () -> Component.literal(person.getName().getString() + ": "
                + needs.describe()).withStyle(ChatFormatting.AQUA), true);
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
            Replies.fail(source, Component.literal(
                    "No Person within " + (int) NEAREST_RADIUS + " blocks."));
        }
        return nearest;
    }

    /** {@link #resolveBody} narrowed to a Person, for the commands that are about being one. */
    private static @Nullable Person resolve(CommandSourceStack source) {
        AgentBody body = AgentCommands.resolveBody(source);
        if (body == null) {
            return null;
        }
        if (body instanceof Person person) {
            return person;
        }
        Replies.fail(source, Component.literal(body.entity().getName().getString()
                + " is not a Person — that command is Autarkia's, not Anima's."));
        return null;
    }

    /** Lists the loaded Persons, nearest first: a {@code ✓} on the pinned one, then name, short id,
     *  dimension, and distance. This is how you find out what to {@code select}. */
    private static int listPersons(CommandSourceStack source) {
        MinecraftServer server = source.getServer();
        PersonDirectory directory = PersonDirectory.get(server);
        Vec3 origin = source.getPosition();
        List<Person> loaded = loadedPersons(server);
        if (loaded.isEmpty()) {
            Replies.send(source, () -> Component.literal("No Persons are loaded.").withStyle(ChatFormatting.GRAY));
            return 0;
        }
        Optional<AgentId> pin = AgentSelection.pinned(source);
        loaded.stream()
                .sorted((a, b) -> Double.compare(a.distanceToSqr(origin), b.distanceToSqr(origin)))
                .forEach(person -> {
                    AgentId id = person.agentId();
                    boolean isPinned = id != null && pin.map(id::equals).orElse(false);
                    String name = id == null ? "<spawning>" : directory.nameOf(id).orElse("<unknown>");
                    String dimension = person.level().dimension().identifier().getPath();
                    double distance = Math.sqrt(person.entity().distanceToSqr(origin));
                    String line = String.format(Locale.ROOT, "%s%s  %s  %s  %.1fm",
                            isPinned ? "✓ " : "  ", name, id == null ? "-" : AgentCommands.shortId(id), dimension, distance);
                    Replies.send(source, () -> Component.literal(line)
                            .withStyle(isPinned ? ChatFormatting.AQUA : ChatFormatting.GRAY));
                });
        return loaded.size();
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

    private static int whoisResolved(CommandSourceStack source) {
        Person target = resolve(source);
        if (target == null) return 0;
        report(source, target);
        return 1;
    }

    private static int whoisTargets(CommandSourceStack source,
                                    java.util.Collection<? extends Entity> targets) {
        List<Person> persons = targets.stream().filter(e -> e instanceof Person).map(e -> (Person) e).toList();
        if (persons.isEmpty()) {
            Replies.fail(source, Component.literal("No Person among the selected entities."));
            return 0;
        }
        persons.forEach(person -> report(source, person));
        return persons.size();
    }

    private static void report(CommandSourceStack source, Person person) {
        AgentId id = person.agentId();
        if (id == null) {
            Replies.send(source, () -> Component.literal("Person not yet identified (spawning).")
                    .withStyle(ChatFormatting.GRAY));
            return;
        }
        PersonIdentity identity = PersonDirectory.get(source.getServer()).find(id).orElse(null);
        if (identity == null) {
            Replies.send(source, () -> Component.literal(id + "  <unknown>").withStyle(ChatFormatting.GRAY));
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
        Replies.send(source, () -> line);
    }
}
