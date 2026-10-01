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
import dev.luizloyola.anima.core.territory.ChunkKey;
import dev.luizloyola.anima.core.territory.Claimed;
import dev.luizloyola.anima.core.territory.Reason;
import dev.luizloyola.anima.mod.body.AgentBody;
import dev.luizloyola.anima.mod.command.OpJournal;
import dev.luizloyola.anima.mod.command.Replies;
import dev.luizloyola.anima.mod.command.Subject;
import dev.luizloyola.anima.mod.social.PartyData;
import dev.luizloyola.anima.mod.territory.Territories;
import dev.luizloyola.autarkia.core.board.PartyBoard;
import dev.luizloyola.autarkia.core.direction.Direction;
import dev.luizloyola.autarkia.core.direction.DirectionId;
import dev.luizloyola.autarkia.core.direction.DirectionLine;
import dev.luizloyola.autarkia.core.direction.Home;
import dev.luizloyola.autarkia.core.direction.HomeJudge;
import dev.luizloyola.autarkia.core.direction.HomeJudge.Candidate;
import dev.luizloyola.autarkia.core.direction.HomeKnob;
import dev.luizloyola.autarkia.core.direction.Lines;
import dev.luizloyola.autarkia.core.direction.Node;
import dev.luizloyola.autarkia.core.direction.NodeKind;
import dev.luizloyola.autarkia.core.direction.PartyProgress;
import dev.luizloyola.autarkia.core.direction.PartyView;
import dev.luizloyola.autarkia.core.direction.Status;
import dev.luizloyola.autarkia.core.direction.Tree;
import dev.luizloyola.autarkia.mod.board.PartyBoards;
import dev.luizloyola.autarkia.mod.debug.HomeChoiceViewer;
import dev.luizloyola.autarkia.mod.direction.Directions;
import dev.luizloyola.autarkia.mod.direction.DirectionsData;
import dev.luizloyola.autarkia.mod.direction.HomeChooser;
import dev.luizloyola.autarkia.mod.entity.Person;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import org.jspecify.annotations.Nullable;

/**
 * Layer 4 at the command line: {@code directions} reads a party's climb and lets an operator grant
 * or revoke a node; {@code home} sets the plot the Directions work on, or judges the plots around
 * the subject ({@code home choose}). Subject-scoped, like the
 * board — the party read or changed is the subject's.
 */
public final class DirectionsCommands {

    private DirectionsCommands() {
    }

    /** Smallest and largest HOME, in blocks from the centre to an edge. */
    private static final int HOME_MIN_RADIUS = 4;
    private static final int HOME_MAX_RADIUS = 64;

    /** How far {@code home choose} may look, to plot centres; its default is {@code home.read_radius}. */
    private static final int CHOOSE_MIN_RADIUS = 16;
    private static final int CHOOSE_MAX_RADIUS = 128;

    /** Plots {@code home choose} lists and paints. */
    private static final int CHOOSE_SHOWN = 5;

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
                .then(Commands.literal("clear").executes(DirectionsCommands::homeClear))
                .then(Commands.literal("choose")
                        .executes(ctx -> homeChoose(ctx, 0, false))
                        .then(Commands.literal("apply").executes(ctx -> homeChoose(ctx, 0, true)))
                        .then(Commands.argument("radius",
                                        IntegerArgumentType.integer(CHOOSE_MIN_RADIUS, CHOOSE_MAX_RADIUS))
                                .executes(ctx -> homeChoose(ctx,
                                        IntegerArgumentType.getInteger(ctx, "radius"), false))
                                .then(Commands.literal("apply").executes(ctx -> homeChoose(ctx,
                                        IntegerArgumentType.getInteger(ctx, "radius"), true)))));
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
        PartyView view = Directions.view(server, party, progress);
        send(source, homeLine(progress.home(), view.area(), view.spot()));
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

    private static Component homeLine(@Nullable Home home, Set<ChunkKey> area, Optional<Pos> spot) {
        if (home == null) {
            return Component.translatable("autarkia.command.directions.no_home")
                    .withStyle(ChatFormatting.GRAY);
        }
        return Component.translatable("autarkia.command.directions.home", area.size(),
                area.size() - home.uncleared(area).size(), spot.map(DirectionsCommands::at).orElse("—"));
    }

    /** {@code [x, z]} of the area's first and last chunks along each axis. */
    private static String[] corners(Set<ChunkKey> area) {
        int minX = Integer.MAX_VALUE;
        int minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int maxZ = Integer.MIN_VALUE;
        for (ChunkKey chunk : area) {
            minX = Math.min(minX, chunk.x());
            minZ = Math.min(minZ, chunk.z());
            maxX = Math.max(maxX, chunk.x());
            maxZ = Math.max(maxZ, chunk.z());
        }
        return area.isEmpty() ? new String[] {"—", "—"}
                : new String[] {"[" + minX + ", " + minZ + "]", "[" + maxX + ", " + maxZ + "]"};
    }

    private static Set<ChunkKey> areaOf(MinecraftServer server, PartyId party) {
        return Territories.of(server).area(party);
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
        if (home == null) {
            send(source, Component.translatable("autarkia.command.home.none", person.getName()));
            return 1;
        }
        Set<ChunkKey> area = areaOf(source.getServer(), party);
        String[] corners = corners(area);
        Optional<Pos> spot = DirectionsData.get(source.getServer()).find(party)
                .flatMap(progress -> Directions.view(source.getServer(), party, progress).spot());
        send(source, Component.translatable("autarkia.command.home.show", person.getName(), area.size(),
                area.size() - home.uncleared(area).size(), corners[0], corners[1],
                spot.map(DirectionsCommands::at).orElse("—")));
        return 1;
    }

    /**
     * The chunks a square round a centre touches. Setting it again is a new HOME, and a new HOME is
     * one nobody has cleared.
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
        Pos centre = new Pos(center.getX(), center.getY(), center.getZ());
        MinecraftServer server = source.getServer();
        Claimed claimed = Directions.settle(server, party, Home.square(centre, radius),
                Reason.of(Reason.Kind.OP, "home set by " + source.getTextName()));
        if (!claimed.granted()) {
            Replies.fail(source, Component.translatable("autarkia.command.home.refused", person.getName(),
                    claimed.describe()));
            return 0;
        }
        Set<ChunkKey> area = areaOf(server, party);
        String[] corners = corners(area);
        OpJournal.record(source, PartyData.get(server).members(party),
                "set HOME round " + at(centre) + ", " + area.size() + " chunks");
        Replies.send(source, () -> Component.translatable("autarkia.command.home.set", person.getName(),
                area.size(), corners[0], corners[1]).withStyle(ChatFormatting.LIGHT_PURPLE), true);
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
        Directions.unsettle(server, party, Reason.of(Reason.Kind.OP, "home clear by " + source.getTextName()));
        OpJournal.record(source, PartyData.get(server).members(party), "cleared HOME");
        Replies.send(source, () -> Component.translatable("autarkia.command.home.cleared",
                person.getName()).withStyle(ChatFormatting.LIGHT_PURPLE), true);
        return 1;
    }

    /**
     * The judge around the subject, as a dry run: the best plots that share no column, each want's
     * part, and the bar its party would face at a first stop. {@code apply} claims the best.
     */
    private static int homeChoose(CommandContext<CommandSourceStack> ctx, int radius, boolean apply) {
        CommandSourceStack source = ctx.getSource();
        Person person = person(ctx);
        PartyId party = person == null ? null : partyOf(source, person);
        if (party == null || !(person.level() instanceof ServerLevel level)) {
            return 0;
        }
        int r = radius > 0 ? radius : HomeKnob.READ_RADIUS.i();
        HomeChooser.Choice choice = HomeChooser.choose(level, person.blockPosition(), r, party,
                person.brain().knowledge());
        List<Candidate> ranked = choice.judgement().ranked();
        List<Candidate> shown = HomeJudge.apart(ranked, CHOOSE_SHOWN);
        ServerPlayer viewer = source.getPlayer();
        if (viewer != null) {
            HomeChoiceViewer.show(viewer, shown);
        }
        Component refused = refusals(choice.judgement().refused());
        if (shown.isEmpty()) {
            Replies.fail(source, Component.translatable("autarkia.command.home.choose.none", r,
                    person.getName()));
            send(source, indent(refused));
            return 0;
        }
        send(source, Component.translatable("autarkia.command.home.choose.header", person.getName(), r,
                ranked.size(), Math.round(choice.table().bar(0)))
                .withStyle(ChatFormatting.LIGHT_PURPLE));
        send(source, indent(refused));
        for (int i = 0; i < shown.size(); i++) {
            Candidate plot = shown.get(i);
            send(source, indent(Component.translatable("autarkia.command.home.choose.candidate", i + 1,
                    plot.x() + " " + plot.y() + " " + plot.z(), Math.round(plot.value()), wants(plot))));
        }
        if (!apply) {
            return 1;
        }
        Candidate best = shown.get(0);
        Pos centre = new Pos(best.x(), best.y() + 1, best.z());
        MinecraftServer server = source.getServer();
        Claimed claimed = Directions.settle(server, party, Home.square(centre, best.size() / 2),
                Reason.of(Reason.Kind.OP, "home choose by " + source.getTextName() + ", worth "
                        + Math.round(best.value())));
        if (!claimed.granted()) {
            Replies.fail(source, Component.translatable("autarkia.command.home.refused", person.getName(),
                    claimed.describe()));
            return 0;
        }
        Set<ChunkKey> area = areaOf(server, party);
        String[] corners = corners(area);
        OpJournal.record(source, PartyData.get(server).members(party),
                "chose HOME at " + at(centre) + ", worth " + Math.round(best.value()));
        Replies.send(source, () -> Component.translatable("autarkia.command.home.choose.applied",
                person.getName(), at(centre), Math.round(best.value()), area.size(), corners[0], corners[1])
                .withStyle(ChatFormatting.LIGHT_PURPLE), true);
        return 1;
    }

    /** How many plots the ground allowed and something refused, by what. */
    private static Component refusals(Map<HomeJudge.Refusal, Integer> refused) {
        if (refused.isEmpty()) {
            return Component.translatable("autarkia.command.home.choose.refused_none");
        }
        List<Component> parts = new ArrayList<>();
        refused.forEach((why, count) -> parts.add(Component.translatable(
                "autarkia.command.home.choose.refused_by", count,
                Component.translatable("autarkia.home.refusal." + why.key()))));
        return Component.translatable("autarkia.command.home.choose.refused", join(parts));
    }

    /** A plot's breakdown: each want, how far or how much, and its points. */
    private static Component wants(Candidate plot) {
        List<Component> parts = new ArrayList<>();
        for (HomeJudge.Line line : plot.lines()) {
            Component name = Component.translatable("autarkia.home.want." + line.want().key());
            long points = Math.round(line.points());
            if (line.want() == HomeJudge.Want.ROOM) {
                parts.add(Component.translatable("autarkia.command.home.choose.want.room", name,
                        Math.round(line.measure()), points));
            } else if (Double.isInfinite(line.measure())) {
                parts.add(Component.translatable("autarkia.command.home.choose.want.none", name));
            } else {
                parts.add(Component.translatable("autarkia.command.home.choose.want.distance", name,
                        Math.round(line.measure()), points));
            }
        }
        return join(parts);
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
            case UNKNOWN, WAITING -> ChatFormatting.GRAY;
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
