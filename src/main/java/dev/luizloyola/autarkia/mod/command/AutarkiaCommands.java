package dev.luizloyola.autarkia.mod.command;

import java.util.Map;
import dev.luizloyola.anima.mod.identity.AgentDirectory;
import dev.luizloyola.anima.core.agent.PrivateIdentity;
import dev.luizloyola.anima.mod.body.AgentBodies;
import dev.luizloyola.anima.mod.body.AgentBody;
import dev.luizloyola.anima.mod.command.AgentCommands;
import dev.luizloyola.anima.mod.command.AgentSelection;
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
import dev.luizloyola.autarkia.core.tree.ChopNearestTree;
import dev.luizloyola.anima.core.brain.task.GoTo;
import dev.luizloyola.anima.core.brain.task.ObtainItem;
import dev.luizloyola.anima.core.brain.task.SatisfyHunger;
import dev.luizloyola.anima.core.config.ConfigValues;
import dev.luizloyola.anima.core.config.Config;
import dev.luizloyola.anima.core.config.Knob;
import dev.luizloyola.anima.core.inv.ArmorType;
import dev.luizloyola.anima.core.inv.Inventory;
import dev.luizloyola.anima.core.inv.ItemSpec;
import dev.luizloyola.autarkia.core.board.Stock;
import dev.luizloyola.anima.core.log.Category;
import dev.luizloyola.anima.core.log.Entry;
import dev.luizloyola.anima.core.log.JournalService;
import dev.luizloyola.autarkia.core.person.Appearance;
import dev.luizloyola.anima.core.agent.Needs;
import dev.luizloyola.anima.core.agent.AgentId;
import dev.luizloyola.autarkia.core.person.PersonIdentity;
import dev.luizloyola.anima.mod.brain.KnowledgeViewer;
import dev.luizloyola.anima.mod.brain.Knowledges;
import dev.luizloyola.anima.mod.brain.BeingViewer;
import dev.luizloyola.anima.mod.debug.DebugLayer;
import dev.luizloyola.anima.mod.debug.DebugView;
import dev.luizloyola.autarkia.mod.entity.ModEntities;
import dev.luizloyola.autarkia.mod.entity.Persons;
import dev.luizloyola.autarkia.mod.entity.Person;
import dev.luizloyola.anima.mod.log.Journals;
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

import java.util.ArrayList;
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
 * <p>{@code whois} prints a Person's identity (id + name), read straight from the server-side
 * {@link PersonDirectory}: the name is never synced to clients.
 *
 * <p>{@code nav} drives the legs directly (locomotion debug) where {@code brain} runs the same work
 * through the task machinery the arbiter feeds; {@code brain auto true | false} flips autonomy — ON
 * by default, and a manual {@code goto}/{@code eat} flips it OFF the moment it runs.
 *
 * <p><b>Every {@code true|false} switch reads back when you leave the value off</b>, changing
 * nothing and returning 1 for on, 0 for off — usable from a command block or an {@code execute if}
 * without parsing the chat line.
 *
 * <p>{@code log} reads the resolved Person's in-memory journal ring, all subsystems or one;
 * {@code log for <name|id>} reaches any person by directory lookup, including one whose entity is
 * unloaded (the ring is {@code AgentId}-keyed and outlives the entity). The durable per-person file
 * is separate.
 *
 * <p>{@code person spawn [<pos>] [name]} registers an identity in the {@link PersonDirectory} and
 * links it to the entity before it enters the world; a plain {@code /summon autarkia:person}
 * instead mints one on the entity's first server tick. Position mirrors {@code /summon}.
 * {@code person spawn nobrain} is that same path with autonomy off: an inert body for exercising
 * one feature at a time.
 *
 * <p>Every person-scoped subcommand resolves through {@link #resolve}: the Person the command runs
 * <em>as</em> (one line, every Person in turn), else the source's pin, else the nearest.
 * {@code select} pins by name or short-id, or by what a player is looking at, unpinning when they
 * look at nobody; {@code list} enumerates the loaded Persons, since names are not unique. Pins live
 * in {@link AgentSelection} — in memory, per source, gone on restart.
 */
public final class AutarkiaCommands {
    private AutarkiaCommands() {}

    private static final double NEAREST_RADIUS = 32.0;

    public static void register() {
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
                        .then(AgentCommands.nav())
                        // nav (above) drives the legs directly — locomotion debug; brain runs
                        // tasks through the executor, the machinery the arbiter will feed.
                        // Anima's shared brain verbs, plus the two that are ours: chop is tree
                        // content and obtain is a log quota — neither is the library's business.
                        .then(AgentCommands.brain()
                                .then(Commands.literal("chop")
                                        .executes(ctx -> brainChop(ctx.getSource())))
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
                        // The in-world debug view: gizmo lines, boxes and floating text over the
                        // SELECTED Person. Per-player, and the only way to raise several layers at
                        // once (the wand's shift-click cycles them one at a time).
                        .then(AgentCommands.debug())
                        // NOTE: the tunables moved with the mind. Every knob Autarkia had was a knob of the
                        // BRAIN, so they live in Anima's own file and command now: /anima config. Autarkia
                        // registers its own set the day it grows a knob about being a PERSON, not a mind.
                        .then(Commands.literal("board")
                                .executes(ctx -> boardShow(ctx.getSource())))
                        // Who they can currently SEE — the peers() sense: Persons and live
                        // players, one seamless list, activity read off the visible body.
                        .then(AgentCommands.peers())
                        .then(AgentCommands.inv(registryAccess))
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

    /** Purges every identity with no loaded entity — dev hygiene for test-world churn. */
    private static int purgeGraveyard(CommandSourceStack source) {
        MinecraftServer server = source.getServer();
        PersonDirectory directory = PersonDirectory.get(server);
        java.util.Set<AgentId> loaded = new java.util.HashSet<>();
        for (Person person : loadedPersons(server)) {
            if (person.agentId() != null) {
                loaded.add(person.agentId());
            }
        }
        List<AgentId> dead = new ArrayList<>();
        for (PersonIdentity identity : directory.all()) {
            if (!loaded.contains(identity.id())) {
                dead.add(identity.id());
            }
        }
        var knowledge = Knowledges.of(server);
        JournalService journals = Journals.of(server);
        for (AgentId id : dead) {
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

    /** Runs {@link ObtainItem} (logs × count): rounds of scavenge-or-chop until the pack holds the
     *  quota, run to completion in one invocation. */
    private static int brainObtain(CommandSourceStack source, int count) {
        Person person = resolve(source);
        if (person == null) return 0;
        boolean autoDisabled = person.brain().run(new ObtainItem(Stock.LOGS, count));
        String suffix = AgentCommands.autoDisabledSuffix(autoDisabled);
        source.sendSuccess(() -> Component.literal(person.getName().getString() + ": "
                + person.brain().describe() + suffix).withStyle(ChatFormatting.AQUA), false);
        return 1;
    }

    /** Runs {@link ChopNearestTree} on the resolved Person — the full chop choreography against
     *  their nearest REMEMBERED grove (knowledge-driven: no memory of a tree, no chop). */
    private static int brainChop(CommandSourceStack source) {
        Person person = resolve(source);
        if (person == null) return 0;
        boolean autoDisabled = person.brain().run(new ChopNearestTree());
        String suffix = AgentCommands.autoDisabledSuffix(autoDisabled);
        source.sendSuccess(() -> Component.literal(person.getName().getString() + ": "
                + person.brain().describe() + suffix).withStyle(ChatFormatting.AQUA), false);
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

    /** Prints the resolved Person's need levels — the {@code needs().describe()} one-liner. */
    private static int personNeeds(CommandSourceStack source) {
        Person person = resolve(source);
        if (person == null) return 0;
        source.sendSuccess(() -> Component.literal(person.getName().getString() + ": "
                + person.needs().describe()).withStyle(ChatFormatting.AQUA), false);
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
        source.sendSuccess(() -> Component.literal(person.getName().getString() + ": "
                + needs.describe()).withStyle(ChatFormatting.AQUA), false);
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
            source.sendFailure(Component.literal(
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
        source.sendFailure(Component.literal(body.entity().getName().getString()
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
            source.sendSuccess(() -> Component.literal("No Persons are loaded.").withStyle(ChatFormatting.GRAY), false);
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
                    source.sendSuccess(() -> Component.literal(line)
                            .withStyle(isPinned ? ChatFormatting.AQUA : ChatFormatting.GRAY), false);
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
            source.sendFailure(Component.literal("No Person among the selected entities."));
            return 0;
        }
        persons.forEach(person -> report(source, person));
        return persons.size();
    }

    private static void report(CommandSourceStack source, Person person) {
        AgentId id = person.agentId();
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
