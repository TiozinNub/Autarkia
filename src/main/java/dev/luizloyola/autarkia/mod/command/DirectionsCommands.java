package dev.luizloyola.autarkia.mod.command;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import dev.luizloyola.anima.core.agent.AgentId;
import dev.luizloyola.anima.core.brain.knowledge.Region;
import dev.luizloyola.anima.core.brain.sense.Pos;
import dev.luizloyola.anima.core.social.PartyId;
import dev.luizloyola.anima.mod.body.AgentBody;
import dev.luizloyola.anima.mod.command.OpJournal;
import dev.luizloyola.anima.mod.command.Replies;
import dev.luizloyola.anima.mod.command.Subject;
import dev.luizloyola.anima.mod.social.PartyData;
import dev.luizloyola.autarkia.core.board.PartyBoard;
import dev.luizloyola.autarkia.core.direction.Direction;
import dev.luizloyola.autarkia.core.direction.DirectionId;
import dev.luizloyola.autarkia.core.direction.DirectionLine;
import dev.luizloyola.autarkia.core.direction.Home;
import dev.luizloyola.autarkia.core.direction.Lines;
import dev.luizloyola.autarkia.core.direction.Node;
import dev.luizloyola.autarkia.core.direction.NodeKind;
import dev.luizloyola.autarkia.core.direction.PartyProgress;
import dev.luizloyola.autarkia.core.direction.PartyView;
import dev.luizloyola.autarkia.core.direction.Status;
import dev.luizloyola.autarkia.core.direction.Tree;
import dev.luizloyola.autarkia.mod.board.PartyBoards;
import dev.luizloyola.autarkia.mod.direction.Directions;
import dev.luizloyola.autarkia.mod.direction.DirectionsData;
import dev.luizloyola.autarkia.mod.entity.Person;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.MinecraftServer;
import org.jspecify.annotations.Nullable;

/**
 * Layer 4 at the command line: {@code directions} reads a party's climb and lets an operator grant
 * or revoke a node; {@code home} sets the plot the Directions work on. Subject-scoped, like the
 * board — the party read or changed is the subject's.
 */
public final class DirectionsCommands {

    private DirectionsCommands() {
    }

    /** Smallest and largest HOME, in blocks from the centre to an edge. */
    private static final int HOME_MIN_RADIUS = 4;
    private static final int HOME_MAX_RADIUS = 64;

    /**
     * How far HOME reaches below and above its centre. A plot is ground, but its clearing must take
     * in a whole tree standing on it, crown and all, and the ground is rarely flat.
     */
    private static final int HOME_BELOW = 16;
    private static final int HOME_ABOVE = 48;

    /** A node's path, or its whole id in quotes — a word cannot hold the colon. */
    private static final SuggestionProvider<CommandSourceStack> NODES = (ctx, builder) ->
            SharedSuggestionProvider.suggest(Directions.tree().nodes().stream()
                    .map(node -> node.id().substring(node.id().indexOf(':') + 1)), builder);

    public static LiteralArgumentBuilder<CommandSourceStack> directions() {
        return Commands.literal("directions")
                .executes(DirectionsCommands::show)
                .then(Commands.literal("grant")
                        .then(Commands.argument("node", StringArgumentType.string()).suggests(NODES)
                                .executes(ctx -> grant(ctx, false))
                                .then(Commands.literal("person").executes(ctx -> grant(ctx, true)))))
                .then(Commands.literal("revoke")
                        .then(Commands.argument("node", StringArgumentType.string()).suggests(NODES)
                                .executes(ctx -> revoke(ctx, false))
                                .then(Commands.literal("person").executes(ctx -> revoke(ctx, true)))));
    }

    public static LiteralArgumentBuilder<CommandSourceStack> home() {
        return Commands.literal("home")
                .executes(DirectionsCommands::homeShow)
                .then(Commands.literal("set")
                        .then(Commands.argument("pos", BlockPosArgument.blockPos())
                                .then(Commands.argument("radius",
                                                IntegerArgumentType.integer(HOME_MIN_RADIUS, HOME_MAX_RADIUS))
                                        .executes(DirectionsCommands::homeSet))))
                .then(Commands.literal("clear").executes(DirectionsCommands::homeClear));
    }

    // ── the readout ─────────────────────────────────────────────────────────────────────────

    private static int show(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        Person person = person(ctx);
        PartyId party = person == null ? null : partyOf(source, person);
        if (party == null) {
            return 0;
        }
        MinecraftServer server = source.getServer();
        Tree tree = Directions.tree();
        PartyProgress progress = DirectionsData.get(server).find(party).orElseGet(PartyProgress::new);
        Set<String> reached = tree.withRoots(progress.reached());
        send(source, Component.translatable("autarkia.command.directions.header", person.getName())
                .withStyle(ChatFormatting.LIGHT_PURPLE));
        if (!Directions.refused().isEmpty()) {
            send(source, Component.translatable("autarkia.command.directions.refused",
                    Directions.refused().size()).withStyle(ChatFormatting.RED));
        }
        if (tree.isEmpty()) {
            send(source, Component.translatable("autarkia.command.directions.no_tree")
                    .withStyle(ChatFormatting.GRAY));
            return 1;
        }
        send(source, Component.translatable("autarkia.command.directions.age",
                tree.age(progress.reached()).map(DirectionsCommands::age)
                        .orElse(Component.translatable("autarkia.command.directions.none"))));
        List<Component> learned = new ArrayList<>();
        for (Node node : tree.nodes()) {
            if (node.kind() == NodeKind.SIDE && reached.contains(node.id())) {
                learned.add(name(node.id()));
            }
        }
        if (!learned.isEmpty()) {
            send(source, Component.translatable("autarkia.command.directions.learned", join(learned)));
        }
        send(source, homeLine(progress.home()));
        PartyView view = Directions.view(server, party, progress);
        PartyBoard board = PartyBoards.of(server, party);
        send(source, Component.translatable("autarkia.command.directions.in_force"));
        for (Direction direction : tree.inForce(progress.reached())) {
            Optional<DirectionLine> kind = Lines.byId(direction.line());
            Status status = kind.map(line -> line.judge(direction, view)).orElse(Status.UNREAD);
            MutableComponent line = Component.translatable("autarkia.command.directions.line",
                    lineName(direction.line()), name(direction.id().node()),
                    Component.translatable(status.langKey(), status.args().toArray()))
                    .withStyle(colour(status.reading()));
            // Found the way the beat finds it, so the handle shows even before the first beat
            // after a restart.
            kind.flatMap(known -> board.projects().stream()
                            .filter(project -> known.isWork(project, direction, view)).findFirst())
                    .ifPresent(work -> board.handleOf(work).ifPresent(handle -> line.append(
                            Component.translatable("autarkia.command.directions.posted", handle))));
            send(source, indent(line));
        }
        send(source, Component.translatable("autarkia.command.directions.checkpoints",
                progress.checkpoints().isEmpty()
                        ? Component.translatable("autarkia.command.directions.none")
                        : Component.literal(String.join(", ",
                                progress.checkpoints().stream().map(DirectionId::toString).toList()))));
        send(source, Component.translatable("autarkia.command.directions.next"));
        boolean any = false;
        for (Node node : tree.nodes()) {
            if (reached.contains(node.id()) || !tree.parentsMet(node, reached)) {
                continue;
            }
            any = true;
            send(source, indent(nextLine(node, progress.checkpoints())));
        }
        if (!any) {
            send(source, indent(Component.translatable("autarkia.command.directions.none")));
        }
        return 1;
    }

    /** One node the party could reach next, and how far its requirements have got. */
    private static Component nextLine(Node node, Set<DirectionId> checkpoints) {
        MutableComponent line = Component.empty().append(age(node)).append(": ");
        DirectionId key = node.requirements().key();
        if (key != null) {
            line.append(Component.translatable("autarkia.command.directions.key",
                    Component.literal(key.toString())
                            .withStyle(checkpoints.contains(key) ? ChatFormatting.GREEN : ChatFormatting.RED)));
        }
        List<DirectionId> pool = node.requirements().pool();
        if (!pool.isEmpty()) {
            List<Component> each = new ArrayList<>();
            for (DirectionId id : pool) {
                each.add(Component.literal(id.toString())
                        .withStyle(checkpoints.contains(id) ? ChatFormatting.GREEN : ChatFormatting.RED));
            }
            if (key != null) {
                line.append(", ");
            }
            line.append(Component.translatable("autarkia.command.directions.pool",
                    node.requirements().poolMet(checkpoints), node.requirements().of(), join(each)));
        }
        if (key == null && pool.isEmpty()) {
            line.append(Component.translatable("autarkia.command.directions.nothing_required"));
        }
        return line;
    }

    private static Component homeLine(@Nullable Home home) {
        if (home == null) {
            return Component.translatable("autarkia.command.directions.no_home")
                    .withStyle(ChatFormatting.GRAY);
        }
        return Component.translatable(home.cleared()
                        ? "autarkia.command.directions.home_cleared"
                        : "autarkia.command.directions.home",
                at(home.plot().min()), at(home.plot().max()), at(home.yard()));
    }

    // ── grant and revoke ────────────────────────────────────────────────────────────────────

    private static int grant(CommandContext<CommandSourceStack> ctx, boolean personOnly) {
        CommandSourceStack source = ctx.getSource();
        Person person = person(ctx);
        PartyId party = person == null ? null : partyOf(source, person);
        Node node = party == null ? null : node(ctx);
        if (node == null) {
            return 0;
        }
        MinecraftServer server = source.getServer();
        AgentId who = person.getAgentId();
        Set<String> added = personOnly
                ? Directions.grant(server, who, node.id())
                : Directions.grant(server, party, node.id());
        if (added.isEmpty()) {
            Replies.fail(source, Component.translatable("autarkia.command.directions.already",
                    person.getName(), age(node)));
            return 0;
        }
        List<AgentId> touched = personOnly ? List.of(who) : PartyData.get(server).members(party);
        // LOGGED: a grant changes durable state that decides what a settler may make.
        OpJournal.record(source, touched, "granted " + String.join(", ", added));
        Replies.send(source, () -> Component.translatable(personOnly
                        ? "autarkia.command.directions.granted_person"
                        : "autarkia.command.directions.granted",
                person.getName(), ids(added)).withStyle(ChatFormatting.LIGHT_PURPLE), true);
        return 1;
    }

    /**
     * Takes a node back, with everything below it. From a party it also takes the checkpoints that
     * earned those nodes, so the next beat reaches one again only if the world still says so.
     */
    private static int revoke(CommandContext<CommandSourceStack> ctx, boolean personOnly) {
        CommandSourceStack source = ctx.getSource();
        Person person = person(ctx);
        PartyId party = person == null ? null : partyOf(source, person);
        Node node = party == null ? null : node(ctx);
        if (node == null) {
            return 0;
        }
        Tree tree = Directions.tree();
        if (tree.roots().contains(node.id())) {
            Replies.fail(source, Component.translatable("autarkia.command.directions.root", age(node)));
            return 0;
        }
        MinecraftServer server = source.getServer();
        AgentId who = person.getAgentId();
        Set<String> gone = personOnly
                ? Directions.revoke(server, who, node.id())
                : Directions.revoke(server, party, node.id());
        if (gone.isEmpty()) {
            Replies.fail(source, Component.translatable("autarkia.command.directions.not_reached",
                    person.getName(), age(node)));
            return 0;
        }
        List<AgentId> touched = personOnly ? List.of(who) : PartyData.get(server).members(party);
        OpJournal.record(source, touched, "revoked " + String.join(", ", gone));
        Replies.send(source, () -> Component.translatable(personOnly
                        ? "autarkia.command.directions.revoked_person"
                        : "autarkia.command.directions.revoked",
                person.getName(), ids(gone)).withStyle(ChatFormatting.LIGHT_PURPLE), true);
        return 1;
    }

    /** The node the command named, by its path alone or its whole id. */
    private static @Nullable Node node(CommandContext<CommandSourceStack> ctx) {
        String written = StringArgumentType.getString(ctx, "node");
        Tree tree = Directions.tree();
        Optional<Node> found = tree.node(written);
        if (found.isEmpty() && !written.contains(":")) {
            List<Node> byPath = tree.nodes().stream()
                    .filter(node -> node.id().substring(node.id().indexOf(':') + 1).equals(written))
                    .toList();
            if (byPath.size() == 1) {
                found = Optional.of(byPath.get(0));
            }
        }
        if (found.isEmpty()) {
            Replies.fail(ctx.getSource(), Component.translatable(
                    "autarkia.command.directions.unknown_node", written));
            return null;
        }
        return found.get();
    }

    // ── HOME ────────────────────────────────────────────────────────────────────────────────

    private static int homeShow(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        Person person = person(ctx);
        PartyId party = person == null ? null : partyOf(source, person);
        if (party == null) {
            return 0;
        }
        Home home = DirectionsData.get(source.getServer()).find(party).map(PartyProgress::home)
                .orElse(null);
        send(source, home == null
                ? Component.translatable("autarkia.command.home.none", person.getName())
                : Component.translatable("autarkia.command.home.show", person.getName(),
                        at(home.plot().min()), at(home.plot().max()), at(home.yard())));
        return 1;
    }

    /**
     * A square plot around a centre, which is also the yard. Setting it again is a new plot, and a
     * new plot is one nobody has cleared.
     */
    private static int homeSet(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        CommandSourceStack source = ctx.getSource();
        Person person = person(ctx);
        PartyId party = person == null ? null : partyOf(source, person);
        if (party == null) {
            return 0;
        }
        BlockPos center = BlockPosArgument.getLoadedBlockPos(ctx, "pos");
        int radius = IntegerArgumentType.getInteger(ctx, "radius");
        Pos yard = new Pos(center.getX(), center.getY(), center.getZ());
        Region plot = new Region(
                new Pos(center.getX() - radius, center.getY() - HOME_BELOW, center.getZ() - radius),
                new Pos(center.getX() + radius, center.getY() + HOME_ABOVE, center.getZ() + radius));
        MinecraftServer server = source.getServer();
        Directions.home(server, party, new Home(plot, yard, false));
        OpJournal.record(source, PartyData.get(server).members(party),
                "set HOME " + at(plot.min()) + " to " + at(plot.max()));
        Replies.send(source, () -> Component.translatable("autarkia.command.home.set", person.getName(),
                at(plot.min()), at(plot.max()), at(yard)).withStyle(ChatFormatting.LIGHT_PURPLE), true);
        return 1;
    }

    private static int homeClear(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        Person person = person(ctx);
        PartyId party = person == null ? null : partyOf(source, person);
        if (party == null) {
            return 0;
        }
        MinecraftServer server = source.getServer();
        Directions.home(server, party, null);
        OpJournal.record(source, PartyData.get(server).members(party), "cleared HOME");
        Replies.send(source, () -> Component.translatable("autarkia.command.home.cleared",
                person.getName()).withStyle(ChatFormatting.LIGHT_PURPLE), true);
        return 1;
    }

    // ── words ───────────────────────────────────────────────────────────────────────────────

    /** A node's own name — {@code Stone}, {@code Glassmaking}. */
    static MutableComponent name(String id) {
        return Component.translatableWithFallback(langKey(id), Directions.plainName(id));
    }

    /** A core node as an age — {@code the Stone Age} — and a side node by its name. */
    static MutableComponent age(Node node) {
        return node.kind() == NodeKind.CORE
                ? Component.translatableWithFallback(langKey(node.id()) + ".age", Directions.label(node))
                : name(node.id());
    }

    private static String langKey(String id) {
        return "autarkia.node." + id.replace(':', '.').replace('/', '.');
    }

    private static MutableComponent lineName(String line) {
        return Component.translatableWithFallback("autarkia.direction.line." + line, line);
    }

    private static ChatFormatting colour(Status.Reading reading) {
        return switch (reading) {
            case MET -> ChatFormatting.GREEN;
            case UNMET -> ChatFormatting.YELLOW;
            case UNKNOWN, NO_HOME -> ChatFormatting.GRAY;
        };
    }

    private static Component ids(Set<String> nodes) {
        List<Component> each = new ArrayList<>();
        for (String id : nodes) {
            each.add(Directions.tree().node(id).map(DirectionsCommands::age)
                    .orElse(Component.literal(id)));
        }
        return join(each);
    }

    private static Component join(List<Component> parts) {
        MutableComponent out = Component.empty();
        for (int i = 0; i < parts.size(); i++) {
            if (i > 0) {
                out.append(", ");
            }
            out.append(parts.get(i));
        }
        return out;
    }

    private static String at(Pos pos) {
        return pos.x() + " " + pos.y() + " " + pos.z();
    }

    private static Component indent(Component line) {
        return Component.literal("  ").append(line);
    }

    private static void send(CommandSourceStack source, Component line) {
        Replies.send(source, () -> line);
    }

    private static @Nullable Person person(CommandContext<CommandSourceStack> ctx) {
        AgentBody body = Subject.body(ctx);
        if (body == null) {
            return null;
        }
        if (body instanceof Person person) {
            return person;
        }
        Replies.fail(ctx.getSource(), Component.translatable("autarkia.command.not_a_person",
                body.entity().getName()));
        return null;
    }

    private static @Nullable PartyId partyOf(CommandSourceStack source, Person person) {
        AgentId who = person.getAgentId();
        if (who == null) {
            Replies.fail(source, Component.translatable("autarkia.command.no_identity", person.getName()));
            return null;
        }
        return PartyData.get(source.getServer()).partyOf(who);
    }
}
