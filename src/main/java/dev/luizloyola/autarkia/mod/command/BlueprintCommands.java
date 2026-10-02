package dev.luizloyola.autarkia.mod.command;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import dev.luizloyola.anima.core.agent.AgentId;
import dev.luizloyola.anima.core.brain.sense.Pos;
import dev.luizloyola.anima.core.social.PartyId;
import dev.luizloyola.anima.core.territory.ChunkKey;
import dev.luizloyola.anima.core.territory.Claimed;
import dev.luizloyola.anima.core.territory.Reason;
import dev.luizloyola.anima.core.territory.Territory;
import dev.luizloyola.anima.mod.command.AgentCommands;
import dev.luizloyola.anima.mod.command.Replies;
import dev.luizloyola.anima.mod.command.OpJournal;
import dev.luizloyola.anima.mod.command.Subject;
import dev.luizloyola.anima.mod.social.PartyData;
import dev.luizloyola.anima.mod.territory.Territories;
import dev.luizloyola.autarkia.compat.bp.BoxReader;
import dev.luizloyola.autarkia.compat.bp.Placer;
import dev.luizloyola.autarkia.core.bp.Blueprint;
import dev.luizloyola.autarkia.core.bp.Blueprint.Facing;
import dev.luizloyola.autarkia.core.bp.Blueprint.SlotInfo;
import dev.luizloyola.autarkia.core.bp.Blueprint.SlotKind;
import dev.luizloyola.autarkia.core.bp.BpCompiler.Compiled;
import dev.luizloyola.autarkia.core.bp.BpText;
import dev.luizloyola.autarkia.core.bp.BuildPlan;
import dev.luizloyola.autarkia.core.bp.BuildPlan.BillLine;
import dev.luizloyola.autarkia.core.bp.Capture;
import dev.luizloyola.autarkia.core.bp.Chooser;
import dev.luizloyola.autarkia.core.bp.Diagnostic;
import dev.luizloyola.autarkia.core.bp.DiagnosticText;
import dev.luizloyola.autarkia.core.bp.Diagnostics;
import dev.luizloyola.autarkia.core.bp.Dictionary;
import dev.luizloyola.autarkia.core.bp.Facts;
import dev.luizloyola.autarkia.core.bp.Footprint;
import dev.luizloyola.autarkia.core.bp.Ids;
import dev.luizloyola.autarkia.core.bp.PlanArgs;
import dev.luizloyola.autarkia.core.bp.Placement;
import dev.luizloyola.autarkia.core.builder.HouseSite;
import dev.luizloyola.autarkia.core.builder.Structure;
import dev.luizloyola.autarkia.mod.builder.Structures;
import dev.luizloyola.autarkia.mod.builder.StructuresData;
import dev.luizloyola.autarkia.mod.builder.HouseSites;
import dev.luizloyola.autarkia.mod.builder.PartyStock;
import dev.luizloyola.autarkia.mod.debug.HouseSiteViewer;
import dev.luizloyola.autarkia.core.bp.Planner;
import dev.luizloyola.autarkia.core.bp.Stock;
import dev.luizloyola.autarkia.core.bp.Variants;
import dev.luizloyola.autarkia.core.builder.BuildOrder;
import dev.luizloyola.autarkia.core.builder.Section;
import dev.luizloyola.autarkia.core.builder.Sections;
import dev.luizloyola.autarkia.core.builder.Step;
import dev.luizloyola.autarkia.core.direction.Node;
import dev.luizloyola.autarkia.core.direction.Tree;
import dev.luizloyola.autarkia.mod.bp.Blueprints;
import dev.luizloyola.autarkia.mod.bp.Captures;
import dev.luizloyola.autarkia.mod.bp.SectionViews;
import dev.luizloyola.autarkia.mod.bp.SlowPlacements;
import dev.luizloyola.autarkia.mod.bp.Blueprints.Entry;
import dev.luizloyola.autarkia.mod.direction.Directions;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.SortedSet;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.IdentifierArgument;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FontDescription;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import org.jspecify.annotations.Nullable;

/**
 * {@code /autarkia bp}: the blueprint library at the command line. What a blueprint says stays
 * literal — its diagnostics (Luiz, 2026-09-25: like a TOML parser's complaint), its text, its ids
 * — while the sentences around them are translatable.
 */
public final class BlueprintCommands {

    private BlueprintCommands() {
    }

    private static final SuggestionProvider<CommandSourceStack> IDS = (ctx, builder) ->
            SharedSuggestionProvider.suggest(Blueprints.library().keySet(), builder);

    public static LiteralArgumentBuilder<CommandSourceStack> bp() {
        return Commands.literal("bp")
                .executes(BlueprintCommands::list)
                .then(Commands.literal("list").executes(BlueprintCommands::list))
                .then(Commands.literal("check").then(id(BlueprintCommands::check)))
                .then(Commands.literal("show").then(id(BlueprintCommands::show)
                        .then(Commands.argument("layer", IntegerArgumentType.integer())
                                .executes(ctx -> withEntry(ctx, (source, entry) -> showLayer(source, entry,
                                        IntegerArgumentType.getInteger(ctx, "layer")))))))
                .then(Commands.literal("query").then(id(BlueprintCommands::query)))
                .then(Commands.literal("bill").then(id((source, entry) -> bill(source, entry, ""))
                        .then(words((source, entry, words) -> bill(source, entry, words)))))
                .then(Commands.literal("place").then(id((source, entry) -> place(source, entry, null, ""))
                        .then(Commands.argument("pos", BlockPosArgument.blockPos())
                                .executes(ctx -> {
                                    BlockPos pos = BlockPosArgument.getLoadedBlockPos(ctx, "pos");
                                    return withEntry(ctx, (source, entry) -> place(source, entry, pos, ""));
                                })
                                .then(Commands.argument("words", StringArgumentType.greedyString())
                                        .executes(ctx -> {
                                            BlockPos pos = BlockPosArgument.getLoadedBlockPos(ctx, "pos");
                                            String words = StringArgumentType.getString(ctx, "words");
                                            return withEntry(ctx, (source, entry) -> place(source, entry, pos, words));
                                        })))))
                .then(Commands.literal("sections").then(id((source, entry) -> sections(source, entry, null, ""))
                        .then(Commands.argument("pos", BlockPosArgument.blockPos())
                                .executes(ctx -> {
                                    BlockPos pos = BlockPosArgument.getLoadedBlockPos(ctx, "pos");
                                    return withEntry(ctx, (source, entry) -> sections(source, entry, pos, ""));
                                })
                                .then(Commands.argument("words", StringArgumentType.greedyString())
                                        .executes(ctx -> {
                                            BlockPos pos = BlockPosArgument.getLoadedBlockPos(ctx, "pos");
                                            String words = StringArgumentType.getString(ctx, "words");
                                            return withEntry(ctx, (source, entry) -> sections(source, entry, pos,
                                                    words));
                                        })))))
                // Where the party's next building of this blueprint would go, painted; changes nothing.
                .then(Commands.literal("site").then(id((source, entry) -> site(source, entry, ""))
                        .then(words(BlueprintCommands::site))))
                // A site named here, taken as level: the party's builders put the building up.
                .then(Commands.literal("build").then(id((source, entry) -> build(source, entry, null, ""))
                        .then(Commands.argument("pos", BlockPosArgument.blockPos())
                                .executes(ctx -> {
                                    BlockPos pos = BlockPosArgument.getLoadedBlockPos(ctx, "pos");
                                    return withEntry(ctx, (source, entry) -> build(source, entry, pos, ""));
                                })
                                .then(Commands.argument("words", StringArgumentType.greedyString())
                                        .executes(ctx -> {
                                            BlockPos pos = BlockPosArgument.getLoadedBlockPos(ctx, "pos");
                                            String words = StringArgumentType.getString(ctx, "words");
                                            return withEntry(ctx, (source, entry) -> build(source, entry, pos, words));
                                        })))))
                // The party's buildings from the moment their site is chosen, and how far along.
                .then(Commands.literal("sited").then(Commands.argument("words", StringArgumentType.greedyString())
                        .executes(ctx -> sited(ctx.getSource(), StringArgumentType.getString(ctx, "words")))))
                .then(Commands.literal("capture").then(Commands.argument("name", IdentifierArgument.id())
                        .executes(ctx -> capture(ctx, ""))
                        .then(Commands.argument("words", StringArgumentType.greedyString())
                                .executes(ctx -> capture(ctx, StringArgumentType.getString(ctx, "words"))))))
                .then(Commands.literal("stop").executes(BlueprintCommands::stop))
                .then(Commands.literal("reload").executes(BlueprintCommands::reload))
                .then(Commands.literal("dictionary").executes(BlueprintCommands::dictionary));
    }

    private interface IdCommand {
        int run(CommandSourceStack source, Entry entry);
    }

    /**
     * An id argument, so another can follow it; its path alone will do when one namespace has it — a
     * bare word parses as {@code minecraft:}, which no blueprint of ours is.
     */
    private static com.mojang.brigadier.builder.RequiredArgumentBuilder<CommandSourceStack, Identifier> id(
            IdCommand command) {
        return Commands.argument("id", IdentifierArgument.id()).suggests(IDS)
                .executes(ctx -> withEntry(ctx, command));
    }

    private interface WordsCommand {
        int run(CommandSourceStack source, Entry entry, String words);
    }

    /** Pins, a facing and {@code flip}, as one greedy string: {@link PlanArgs} reads them in any order. */
    private static com.mojang.brigadier.builder.RequiredArgumentBuilder<CommandSourceStack, String> words(
            WordsCommand command) {
        return Commands.argument("words", StringArgumentType.greedyString())
                .executes(ctx -> withEntry(ctx, (source, entry) -> command.run(source, entry,
                        StringArgumentType.getString(ctx, "words"))));
    }

    private static int withEntry(CommandContext<CommandSourceStack> ctx, IdCommand command) {
        Identifier id = IdentifierArgument.getId(ctx, "id");
        Optional<Entry> entry = Blueprints.find(id.toString()).or(() -> Blueprints.find(id.getPath()));
        if (entry.isEmpty()) {
            Replies.fail(ctx.getSource(), Component.translatable("autarkia.command.bp.unknown", id.getPath()));
            return 0;
        }
        return command.run(ctx.getSource(), entry.get());
    }

    // ── list, reload, dictionary ────────────────────────────────────────────────────────────

    private static int list(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        Map<String, Entry> library = Blueprints.library();
        long broken = library.values().stream().filter(entry -> !entry.compiled().ok()).count();
        send(source, Component.translatable("autarkia.command.bp.list.header", library.size() - broken, broken)
                .withStyle(ChatFormatting.LIGHT_PURPLE));
        if (library.isEmpty()) {
            send(source, indent(Component.translatable("autarkia.command.bp.list.empty")
                    .withStyle(ChatFormatting.GRAY)));
        }
        for (Entry entry : library.values()) {
            Compiled compiled = entry.compiled();
            Blueprint bp = compiled.blueprint();
            MutableComponent line;
            if (bp == null) {
                line = Component.translatable("autarkia.command.bp.list.broken", entry.id(), compiled.errors())
                        .withStyle(ChatFormatting.RED);
            } else {
                line = Component.translatable("autarkia.command.bp.list.ok", entry.id(), bp.headers().name(),
                        bp.headers().version(), bp.width(), bp.depth(), bp.layers());
                if (compiled.reports() > 0) {
                    line.append(Component.translatable("autarkia.command.bp.list.reports", compiled.reports())
                            .withStyle(ChatFormatting.YELLOW));
                }
            }
            send(source, indent(line));
        }
        return library.size();
    }

    private static int reload(CommandContext<CommandSourceStack> ctx) {
        Blueprints.reloadConfig();
        Map<String, Entry> library = Blueprints.library();
        long broken = library.values().stream().filter(entry -> !entry.compiled().ok()).count();
        Replies.send(ctx.getSource(), () -> Component.translatable("autarkia.command.bp.reload",
                library.size() - broken, broken), true);
        return library.size();
    }

    private static int dictionary(CommandContext<CommandSourceStack> ctx) {
        Dictionary dict = Blueprints.dictionary();
        try {
            Path file = Blueprints.exportDictionary();
            Replies.send(ctx.getSource(), () -> Component.translatable("autarkia.command.bp.dictionary",
                    file.toString(), dict.blocks().size(), dict.materials().size(), dict.forms().size()), true);
            return 1;
        } catch (IOException e) {
            Replies.fail(ctx.getSource(), Component.translatable("autarkia.command.bp.dictionary.failed",
                    String.valueOf(e.getMessage())));
            return 0;
        }
    }

    // ── check and show ──────────────────────────────────────────────────────────────────────

    private static int check(CommandSourceStack source, Entry entry) {
        Compiled compiled = entry.compiled();
        send(source, Component.translatable("autarkia.command.bp.check.header", entry.id(), entry.origin(),
                compiled.errors(), compiled.reports()).withStyle(ChatFormatting.LIGHT_PURPLE));
        if (compiled.diagnostics().isEmpty()) {
            send(source, indent(Component.translatable("autarkia.command.bp.check.clean")
                    .withStyle(ChatFormatting.GREEN)));
        }
        List<String> lines = DiagnosticText.lines(entry.text());
        for (Diagnostic d : compiled.diagnostics()) {
            send(source, indent(Component.literal(d.summary())
                    .withStyle(d.isError() ? ChatFormatting.RED : ChatFormatting.YELLOW)));
            if (d.line() >= 1 && d.line() <= lines.size()) {
                send(source, indent(indent(pointed(lines.get(d.line() - 1), d.column()))));
            }
        }
        return compiled.ok() ? 1 : 0;
    }

    /** The source line with the column coloured — chat has no fixed-width font for a caret. */
    private static Component pointed(String line, int column) {
        String text = line.replace('\t', ' ');
        if (column < 1 || column > text.length()) {
            return Component.literal(text).withStyle(ChatFormatting.GRAY);
        }
        return Component.literal(text.substring(0, column - 1)).withStyle(ChatFormatting.GRAY)
                .append(Component.literal(text.substring(column - 1, column))
                        .withStyle(ChatFormatting.RED, ChatFormatting.UNDERLINE, ChatFormatting.BOLD))
                .append(Component.literal(text.substring(column)).withStyle(ChatFormatting.GRAY));
    }

    /** Minecraft's fixed-width font: a grid row in the chat font does not line up with the next. */
    private static final Style GRID = Style.EMPTY.withColor(ChatFormatting.GRAY)
            .withFont(new FontDescription.Resource(Identifier.fromNamespaceAndPath("minecraft", "uniform")));

    /**
     * One message per section, and a grid always as a single message: sent row by row, a chat mod that
     * folds repeated lines turns three identical wall rows into one line marked (x3).
     */
    private static int show(CommandSourceStack source, Entry entry) {
        Blueprint bp = entry.compiled().blueprint();
        if (bp == null) {
            Replies.fail(source, Component.translatable("autarkia.command.bp.broken", entry.id()));
            return 0;
        }
        send(source, Component.translatable("autarkia.command.bp.show.header", entry.id())
                .withStyle(ChatFormatting.LIGHT_PURPLE));
        for (String section : BpText.render(bp).split("\n\n")) {
            List<String> lines = List.of(section.strip().split("\n"));
            boolean grid = lines.get(0).startsWith("layer ") || lines.get(0).startsWith("node layer ");
            send(source, grid ? grid(lines.get(0), lines.subList(1, lines.size()))
                    : Component.literal(String.join("\n", lines)).withStyle(ChatFormatting.GRAY));
        }
        return 1;
    }

    /** A single layer, with the legend entries drawn in it — what fits on a screen. */
    private static int showLayer(CommandSourceStack source, Entry entry, int layer) {
        Blueprint bp = entry.compiled().blueprint();
        if (bp == null) {
            Replies.fail(source, Component.translatable("autarkia.command.bp.broken", entry.id()));
            return 0;
        }
        if (layer < bp.minLayer() || layer > bp.maxLayer()) {
            Replies.fail(source, Component.translatable("autarkia.command.bp.show.no_layer", entry.id(),
                    bp.minLayer(), bp.maxLayer(), layer));
            return 0;
        }
        send(source, Component.translatable("autarkia.command.bp.show.layer", entry.id(), layer, bp.minLayer(),
                bp.maxLayer()).withStyle(ChatFormatting.LIGHT_PURPLE));
        // The cells only: a row's node ids would otherwise match the glyphs their letters spell.
        StringBuilder cells = new StringBuilder();
        if (layer >= bp.baseMinLayer() && layer <= bp.baseMaxLayer()) {
            send(source, grid("layer " + layer, BpText.rows(bp, layer)));
            List<String> overlay = BpText.overlayRows(bp, layer);
            if (!overlay.isEmpty()) {
                send(source, grid("node layer " + layer, overlay));
            }
            BpText.rows(bp, layer).forEach(row -> cells.append(row, 0, bp.width()));
        }
        // A grid per variant, each its own message: a folding chat would merge two identical ones.
        for (String key : bp.variants().keys()) {
            List<String> rows = BpText.variantRows(bp, key, layer);
            if (!rows.isEmpty()) {
                send(source, grid("layer " + layer + " " + key, rows));
                rows.forEach(cells::append);
            }
        }
        List<String> used = new ArrayList<>();
        bp.legend().values().forEach(e -> {
            if (cells.indexOf(String.valueOf(e.glyph())) >= 0) {
                used.add(e.glyph() + " " + e.text());
            }
        });
        if (!used.isEmpty()) {
            send(source, Component.literal(String.join("\n", used)).withStyle(ChatFormatting.GRAY));
        }
        return 1;
    }

    private static Component grid(String header, List<String> rows) {
        return Component.literal(header).withStyle(ChatFormatting.GRAY)
                .append(Component.literal("\n" + String.join("\n", rows)).withStyle(GRID));
    }

    // ── query ───────────────────────────────────────────────────────────────────────────────

    private static int query(CommandSourceStack source, Entry entry) {
        Blueprint bp = entry.compiled().blueprint();
        if (bp == null) {
            Replies.fail(source, Component.translatable("autarkia.command.bp.broken", entry.id()));
            return 0;
        }
        Dictionary dict = Blueprints.dictionary();
        Facts facts = Facts.of(bp, dict);
        send(source, Component.translatable("autarkia.command.bp.query.header", entry.id(), bp.headers().name(),
                bp.headers().version(), bp.headers().author()).withStyle(ChatFormatting.LIGHT_PURPLE));
        send(source, indent(Component.translatable("autarkia.command.bp.query.placement", orientation(bp),
                String.valueOf(bp.headers().flippable()))));
        send(source, indent(Component.translatable("autarkia.command.bp.query.size", facts.width(), facts.depth(),
                facts.minLayer(), facts.maxLayer(), facts.placed())));
        for (SlotInfo slot : bp.slots()) {
            send(source, indent(Component.translatable("autarkia.command.bp.query.slot", slot.number(),
                    Component.translatable(slot.kind() == SlotKind.MATERIAL ? "autarkia.command.bp.kind.material"
                            : "autarkia.command.bp.kind.palette"), slot.binding().word(), brief(slot.domain()))));
            if (slot.domain().size() < slot.declared().size()) {
                Set<String> dropped = slot.declared().stream().filter(member -> !slot.domain().contains(member))
                        .collect(Collectors.toSet());
                send(source, indent(indent(Component.translatable("autarkia.command.bp.query.dropped",
                        brief(dropped), String.join(", ", slot.forms())).withStyle(ChatFormatting.GRAY))));
            }
        }
        variants(source, bp, dict, facts);
        facts.countsInLegendOrder(bp).forEach((glyph, count) -> send(source, indent(Component.translatable(
                "autarkia.command.bp.query.entry", String.valueOf(glyph), count,
                bp.legend().get(glyph).text()))));
        send(source, indent(Component.translatable("autarkia.command.bp.query.entrances",
                facts.entrances().isEmpty() ? Component.translatable("autarkia.command.bp.query.none")
                        : Component.literal(facts.entrances().stream().map(e -> e.outward().word())
                                .collect(Collectors.joining(", "))))));
        send(source, indent(Component.translatable("autarkia.command.bp.query.inside", facts.beds(), facts.rooms(),
                facts.roomCells(), facts.roofed(), facts.lights())));
        if (!facts.stations().isEmpty()) {
            send(source, indent(Component.translatable("autarkia.command.bp.query.stations",
                    facts.stations().entrySet().stream().map(e -> e.getKey() + " ×" + e.getValue())
                            .collect(Collectors.joining(", ")))));
        }
        if (!bp.nodes().isEmpty()) {
            send(source, indent(Component.translatable("autarkia.command.bp.query.nodes", bp.nodes().size(),
                    bp.edges().size())));
        }
        Tree tree = Directions.tree();
        List<Facts.Need> needs = Facts.needs(bp, dict, item -> tree.lacksForItem(item, Set.of()).map(Node::id));
        if (needs.isEmpty()) {
            send(source, indent(Component.translatable("autarkia.command.bp.query.ungated")
                    .withStyle(ChatFormatting.GRAY)));
        }
        for (Facts.Need need : needs) {
            send(source, indent(Component.translatable("autarkia.command.bp.query.needs",
                    String.join(" / ", need.nodes()), String.valueOf(need.glyph()))));
        }
        return 1;
    }

    // ── bill and place ──────────────────────────────────────────────────────────────────────

    /**
     * The planned bill: every option settled — pinned, or rolled for this reading — and the
     * bindings printed so the same reading can be asked for again.
     */
    private static int bill(CommandSourceStack source, Entry entry, String words) {
        Blueprint bp = entry.compiled().blueprint();
        if (bp == null) {
            Replies.fail(source, Component.translatable("autarkia.command.bp.broken", entry.id()));
            return 0;
        }
        Diagnostics out = new Diagnostics();
        PlanArgs args = PlanArgs.parse(words, out);
        if (args.facing() != null || args.flip() || args.slow() > 0) {
            Replies.fail(source, Component.translatable("autarkia.command.bp.bill.pins_only"));
            return 0;
        }
        BuildPlan plan = plan(source, entry, bp, args, out);
        if (plan == null) {
            return 0;
        }
        send(source, Component.translatable("autarkia.command.bp.bill.header", entry.id(), bindings(plan))
                .withStyle(ChatFormatting.LIGHT_PURPLE));
        for (BillLine line : plan.bill()) {
            send(source, indent(line.items().size() == 1
                    ? Component.translatable("autarkia.command.bp.bill.one", line.count(), brief(line.items()))
                    : Component.translatable("autarkia.command.bp.bill.any", line.count(), brief(line.items()))));
        }
        plan.itemless().forEach((block, count) -> send(source, indent(Component.translatable(
                "autarkia.command.bp.bill.itemless", count, block).withStyle(ChatFormatting.GRAY))));
        return 1;
    }

    /** Sites painted, best first; the reply names the best few. */
    private static final int SITES_SHOWN = 8;
    private static final int SITES_TOLD = 5;

    /**
     * Where {@code party=<person>}'s party would put this blueprint's next building
     * (docs/superpowers/specs/2026-10-01-house-site-design.md), painted for a minute with each
     * term's price in the reply. Every allowed facing is tried unless the words name one.
     */
    private static int site(CommandSourceStack source, Entry entry, String words) {
        Blueprint bp = entry.compiled().blueprint();
        if (bp == null) {
            Replies.fail(source, Component.translatable("autarkia.command.bp.broken", entry.id()));
            return 0;
        }
        // `apply` is this command's own word: the rest are the plan's.
        List<String> rest = new ArrayList<>(List.of(words.trim().isEmpty() ? new String[0] : words.trim().split("\\s+")));
        boolean apply = rest.remove("apply");
        Diagnostics out = new Diagnostics();
        PlanArgs args = PlanArgs.parse(String.join(" ", rest), out);
        if (out.hasErrors()) {
            failed(source, entry, out);
            return 0;
        }
        if (args.party() == null) {
            Replies.fail(source, Component.translatable("autarkia.command.bp.site.no_party"));
            return 0;
        }
        AgentId who = Subject.directoryId(source, args.party());
        if (who == null) {
            return 0;
        }
        MinecraftServer server = source.getServer();
        PartyId party = PartyData.get(server).partyOf(who);
        BuildPlan plan = plan(source, entry, bp, args, PartyStock.of(server, party), out);
        if (plan == null) {
            return 0;
        }
        HouseSites.Inputs inputs = HouseSites.inputs(server.overworld(), party);
        if (inputs == null) {
            Replies.fail(source, Component.translatable("autarkia.command.bp.site.no_area",
                    AgentCommands.label(server, who)));
            return 0;
        }
        long started = System.nanoTime();
        HouseSite.Result result = HouseSites.choose(inputs, plan, HouseSites.placements(bp, args.facing(), args.flip()));
        long millis = (System.nanoTime() - started) / 1_000_000;
        List<HouseSite.Choice> best = result.best();
        ServerPlayer viewer = source.getPlayer();
        if (viewer != null) {
            HouseSiteViewer.show(viewer, best.subList(0, Math.min(SITES_SHOWN, best.size())));
        }
        StringBuilder reasons = new StringBuilder();
        result.refused().forEach((why, n) -> reasons.append(reasons.isEmpty() ? "" : ", ")
                .append(why.name().toLowerCase(java.util.Locale.ROOT)).append(' ').append(n));
        Component refused = reasons.isEmpty() ? Component.translatable("autarkia.command.bp.site.refused_none")
                : Component.literal(reasons.toString());
        if (best.isEmpty()) {
            Replies.fail(source, Component.translatable("autarkia.command.bp.site.none", entry.id(),
                    AgentCommands.label(server, who), refused));
            return 0;
        }
        Replies.send(source, () -> Component.translatable("autarkia.command.bp.site.header", entry.id(),
                AgentCommands.label(server, who), best.size(), millis, refused)
                .withStyle(ChatFormatting.LIGHT_PURPLE), false);
        for (int i = 0; i < Math.min(SITES_TOLD, best.size()); i++) {
            HouseSite.Choice choice = best.get(i);
            StringBuilder terms = new StringBuilder();
            choice.terms().entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(term -> {
                if (Math.abs(term.getValue()) >= 0.5) {
                    terms.append(terms.isEmpty() ? "" : ", ").append(term.getKey()).append(' ')
                            .append(Math.round(term.getValue()));
                }
            });
            Placement placement = choice.shape().placement();
            int rank = i + 1;
            Replies.send(source, () -> Component.translatable("autarkia.command.bp.site.candidate", rank,
                    choice.anchorX() + " " + choice.y() + " " + choice.anchorZ(),
                    placement.north().word() + (placement.flip() ? " flip" : ""), Math.round(choice.cost()),
                    terms.toString()), false);
        }
        return apply ? applySite(source, entry, plan, party, best.get(0)) : 1;
    }

    /**
     * Takes the best site: the area grows to hold it and the party records it, sited. Its ground is
     * cleared by the area line and its pad then flattened ({@code Structures}); nothing is built.
     */
    private static int applySite(CommandSourceStack source, Entry entry, BuildPlan plan, PartyId party,
                                 HouseSite.Choice choice) {
        MinecraftServer server = source.getServer();
        HouseSites.Applied applied = HouseSites.apply(server, party, entry.id(), plan, choice, source.getTextName());
        if (!applied.grown().granted()) {
            Replies.fail(source, Component.translatable("autarkia.command.bp.site.not_grown",
                    applied.grown().describe()));
            return 0;
        }
        Structure structure = applied.structure();
        OpJournal.record(source, PartyData.get(server).members(party), "sited " + Structures.describe(structure));
        Replies.send(source, () -> Component.translatable("autarkia.command.bp.site.applied",
                Structures.describe(structure), applied.grown().added().size())
                .withStyle(ChatFormatting.LIGHT_PURPLE), true);
        return 1;
    }

    /**
     * {@code bp build <id> [pos] party=<person> [pins]}: the party's building, sited here rather than
     * by the scorer. From there it goes as the house line's does: its chunks cleared, its footprint
     * levelled at this height, then built. Building before the clearing lost a house's posts to it
     * as logs nobody could name.
     */
    private static int build(CommandSourceStack source, Entry entry, @Nullable BlockPos pos, String words) {
        Blueprint bp = entry.compiled().blueprint();
        if (bp == null) {
            Replies.fail(source, Component.translatable("autarkia.command.bp.broken", entry.id()));
            return 0;
        }
        Diagnostics out = new Diagnostics();
        PlanArgs args = PlanArgs.parse(words, out);
        Placement placement = out.hasErrors() ? null : args.placement(bp, out);
        if (placement == null) {
            failed(source, entry, out);
            return 0;
        }
        if (args.party() == null) {
            Replies.fail(source, Component.translatable("autarkia.command.bp.site.no_party"));
            return 0;
        }
        AgentId who = Subject.directoryId(source, args.party());
        if (who == null) {
            return 0;
        }
        BuildPlan plan = plan(source, entry, bp, args,
                PartyStock.of(source.getServer(), PartyData.get(source.getServer()).partyOf(who)), out);
        if (plan == null) {
            return 0;
        }
        BlockPos anchor = pos != null ? pos : BlockPos.containing(source.getPosition()).below();
        Building building = building(source, entry, args.party(), anchor, plan, placement);
        if (building == null) {
            return 0;
        }
        MinecraftServer server = source.getServer();
        Territories.of(server).grow(building.party(), building.footprint(), Territories.margin(), building.why(),
                Territories.now(server));
        Footprint footprint = Footprint.of(anchor.getX(), anchor.getZ(), plan, placement);
        Structure structure = new Structure(java.util.UUID.randomUUID(), entry.id(), plan.version(), plan.variants(),
                plan.bindings(), new Pos(anchor.getX(), anchor.getY(), anchor.getZ()), placement, footprint, footprint,
                Structure.Phase.SITED, server.overworld().getGameTime(), "");
        StructuresData.get(server).add(building.party(), structure);
        OpJournal.record(source, PartyData.get(server).members(building.party()),
                "to build " + Structures.describe(structure));
        Replies.send(source, () -> Component.translatable("autarkia.command.bp.build.recorded",
                Structures.describe(structure), building.who()).withStyle(ChatFormatting.LIGHT_PURPLE), true);
        return 1;
    }

    /** {@code bp sited party=<person>}: the party's buildings, each with how far along it is. */
    private static int sited(CommandSourceStack source, String words) {
        Diagnostics out = new Diagnostics();
        PlanArgs args = PlanArgs.parse(words, out);
        if (out.hasErrors() || args.party() == null) {
            Replies.fail(source, Component.translatable("autarkia.command.bp.site.no_party"));
            return 0;
        }
        AgentId who = Subject.directoryId(source, args.party());
        if (who == null) {
            return 0;
        }
        MinecraftServer server = source.getServer();
        List<Structure> structures = StructuresData.get(server).of(PartyData.get(server).partyOf(who));
        Replies.send(source, () -> Component.translatable("autarkia.command.bp.sited.header",
                AgentCommands.label(server, who), structures.size()), false);
        for (Structure structure : structures) {
            Replies.send(source, () -> Component.literal("  " + Structures.describe(structure)), false);
        }
        return 1;
    }

    /**
     * Plan and write it at once, anchored at the footprint's centre on layer 0 — the given block, or
     * the one under the executor's feet. Authoring only: there is no undo, and no record is kept.
     */
    private static int place(CommandSourceStack source, Entry entry, @Nullable BlockPos pos, String words) {
        Blueprint bp = entry.compiled().blueprint();
        if (bp == null) {
            Replies.fail(source, Component.translatable("autarkia.command.bp.broken", entry.id()));
            return 0;
        }
        Diagnostics out = new Diagnostics();
        PlanArgs args = PlanArgs.parse(words, out);
        Placement placement = out.hasErrors() ? null : args.placement(bp, out);
        if (placement == null) {
            failed(source, entry, out);
            return 0;
        }
        BuildPlan plan = plan(source, entry, bp, args, out);
        if (plan == null) {
            return 0;
        }
        BlockPos anchor = pos != null ? pos : BlockPos.containing(source.getPosition()).below();
        Building building = null;
        if (args.party() != null) {
            building = building(source, entry, args.party(), anchor, plan, placement);
            if (building == null) {
                return 0;
            }
        }
        if (args.slow() > 0) {
            int placed = placeSlowly(source, entry, anchor, plan, placement, args.slow());
            if (placed > 0 && building != null) {
                claim(source, building);
            }
            return placed;
        }
        Placer.Result result = Placer.place(source.getLevel(), anchor, plan, placement);
        if (refused(source, result)) {
            return 0;
        }
        if (building != null) {
            claim(source, building);
        }
        MutableComponent line = Component.translatable("autarkia.command.bp.place.done", entry.id(),
                plan.version(), anchor.getX(), anchor.getY(), anchor.getZ(), placement.north().word(),
                result.blocks(), result.cleared(), result.filled());
        if (placement.flip()) {
            line.append(Component.translatable("autarkia.command.bp.place.flipped"));
        }
        line.append(Component.literal(" — ")).append(bindings(plan));
        Replies.send(source, () -> line, true);
        return 1;
    }

    /** A placement that is a party's building, priced and not yet claimed. */
    private record Building(PartyId party, String who, SortedSet<ChunkKey> footprint, Reason why,
                            boolean overworld) {
    }

    /**
     * Whether the party may have this building: its footprint and margin added to the area must be
     * one piece (docs/superpowers/specs/2026-10-01-home-area-design.md, decision 8). Priced before a
     * block is written, so a refusal places nothing; the refusal is logged like any claim. Null,
     * having said why, when it may not.
     */
    private static @Nullable Building building(CommandSourceStack source, Entry entry, String name, BlockPos anchor,
                                               BuildPlan plan, Placement placement) {
        AgentId who = Subject.directoryId(source, name);
        if (who == null) {
            return null;
        }
        MinecraftServer server = source.getServer();
        PartyId party = PartyData.get(server).partyOf(who);
        String dimension = source.getLevel().dimension().identifier().toString();
        SortedSet<ChunkKey> footprint = Footprint.of(anchor.getX(), anchor.getZ(), plan, placement).chunks(dimension);
        Reason why = Reason.of(Reason.Kind.GROW, entry.id() + " at (" + anchor.getX() + ", " + anchor.getY() + ", "
                + anchor.getZ() + "), placed by " + source.getTextName());
        Territory territory = Territories.of(server);
        Claimed priced = territory.planGrow(party, footprint, Territories.margin(), why, Territories.now(server));
        if (!priced.granted()) {
            territory.grow(party, footprint, Territories.margin(), why, Territories.now(server));
            Replies.fail(source, Component.translatable("autarkia.command.bp.place.party_refused",
                    AgentCommands.label(server, who), priced.describe()));
            return null;
        }
        return new Building(party, AgentCommands.label(server, who), footprint, why,
                source.getLevel() == server.overworld());
    }

    /** The building stands: its ground is the party's, and HOME knows it is ready ground. */
    private static void claim(CommandSourceStack source, Building building) {
        MinecraftServer server = source.getServer();
        Claimed claimed = Territories.of(server).grow(building.party(), building.footprint(), Territories.margin(),
                building.why(), Territories.now(server));
        int holds = Territories.of(server).area(building.party()).size();
        Replies.send(source, () -> Component.translatable("autarkia.command.bp.place.party", building.who(),
                claimed.added().size(), holds).withStyle(ChatFormatting.LIGHT_PURPLE), true);
        // HOME is the overworld's; a building elsewhere is held and nothing more.
        if (building.overworld() && Directions.built(server, building.party(), building.footprint())) {
            Replies.send(source, () -> Component.translatable("autarkia.command.bp.place.party_home",
                    building.who()).withStyle(ChatFormatting.LIGHT_PURPLE), true);
        }
    }

    /**
     * The ground at once, then the blocks a step at a time in the builder's proved order, so the
     * order can be watched; the stand of each is painted beside it.
     */
    private static int placeSlowly(CommandSourceStack source, Entry entry, BlockPos anchor, BuildPlan plan,
                                   Placement placement, int ticks) {
        Placer.Result ground = Placer.groundwork(source.getLevel(), anchor, plan, placement);
        if (refused(source, ground)) {
            return 0;
        }
        BuildOrder.Result order = BuildOrder.prove(plan, Blueprints.dictionary());
        SlowPlacements.start(source, entry.id().toString(), anchor, plan, placement, order.order(), ticks);
        MutableComponent line = Component.translatable("autarkia.command.bp.place.slow.start", entry.id(),
                anchor.getX(), anchor.getY(), anchor.getZ(), placement.north().word(), order.order().size(), ticks);
        if (placement.flip()) {
            line.append(Component.translatable("autarkia.command.bp.place.flipped"));
        }
        line.append(Component.literal(" — ")).append(bindings(plan));
        Replies.send(source, () -> line, true);
        if (!order.complete()) {
            send(source, indent(Component.translatable("autarkia.command.bp.place.slow.unplaced",
                    order.unplaced().size()).withStyle(ChatFormatting.YELLOW)));
        }
        return 1;
    }

    private static boolean refused(CommandSourceStack source, Placer.Result result) {
        switch (result.status()) {
            case UNLOADED -> Replies.fail(source, Component.translatable("autarkia.command.bp.place.unloaded"));
            case OUTSIDE_WORLD -> Replies.fail(source, Component.translatable("autarkia.command.bp.place.outside",
                    result.detail()));
            case UNKNOWN_STATE -> Replies.fail(source, Component.translatable("autarkia.command.bp.place.unknown",
                    result.detail()));
            case PLACED -> {
                return false;
            }
        }
        return true;
    }

    private static int stop(CommandContext<CommandSourceStack> ctx) {
        int stopped = SlowPlacements.stop();
        int cleared = SectionViews.clear(ctx.getSource().getServer());
        Replies.send(ctx.getSource(), () -> Component.translatable("autarkia.command.bp.stop", stopped, cleared),
                true);
        return stopped + cleared;
    }

    /**
     * The plan's sections painted where {@code bp place} would put it, a colour each, for a minute;
     * section names among the words show only those, through whatever stands in front of them.
     */
    private static int sections(CommandSourceStack source, Entry entry, @Nullable BlockPos pos, String words) {
        if (!(source.getEntity() instanceof ServerPlayer player)) {
            Replies.fail(source, Component.translatable("autarkia.command.bp.sections.player"));
            return 0;
        }
        Blueprint bp = entry.compiled().blueprint();
        if (bp == null) {
            Replies.fail(source, Component.translatable("autarkia.command.bp.broken", entry.id()));
            return 0;
        }
        Set<Section> only = EnumSet.noneOf(Section.class);
        List<String> rest = new ArrayList<>();
        for (String word : words.strip().split("\\s+")) {
            Section named = Arrays.stream(Section.values()).filter(s -> s.name().equalsIgnoreCase(word)).findFirst()
                    .orElse(null);
            if (named != null) {
                only.add(named);
            } else {
                rest.add(word);
            }
        }
        Diagnostics out = new Diagnostics();
        PlanArgs args = PlanArgs.parse(String.join(" ", rest), out);
        Placement placement = out.hasErrors() ? null : args.placement(bp, out);
        if (placement == null) {
            failed(source, entry, out);
            return 0;
        }
        BuildPlan plan = plan(source, entry, bp, args, out);
        if (plan == null) {
            return 0;
        }
        BlockPos anchor = pos != null ? pos : BlockPos.containing(source.getPosition()).below();
        Map<Section, List<BlockPos>> cells = new EnumMap<>(Section.class);
        for (Step step : Sections.of(plan, Blueprints.dictionary())) {
            if (only.isEmpty() || only.contains(step.section())) {
                for (dev.luizloyola.autarkia.core.builder.Cell cell : step.cells()) {
                    cells.computeIfAbsent(step.section(), s -> new ArrayList<>())
                            .add(Placer.at(anchor, plan, placement, cell));
                }
            }
        }
        SectionViews.show(player, cells, !only.isEmpty());
        send(source, Component.translatable("autarkia.command.bp.sections.header", entry.id(), anchor.getX(),
                anchor.getY(), anchor.getZ(), placement.north().word()).withStyle(ChatFormatting.LIGHT_PURPLE));
        MutableComponent legend = Component.empty();
        cells.forEach((section, at) -> {
            if (!legend.getSiblings().isEmpty()) {
                legend.append(Component.literal(" · ").withStyle(ChatFormatting.GRAY));
            }
            int colour = SectionViews.colour(section) & 0xFFFFFF;
            legend.append(Component.translatable("autarkia.command.bp.sections.entry",
                    Component.translatable("autarkia.builder.section." + section.name().toLowerCase(Locale.ROOT)),
                    at.size()).withStyle(style -> style.withColor(colour)));
        });
        send(source, indent(legend));
        return 1;
    }

    /** A command's chooser is random (reader spec): the bindings it prints are what to pin for a repeat. */
    private static @Nullable BuildPlan plan(CommandSourceStack source, Entry entry, Blueprint bp, PlanArgs args,
                                            Diagnostics out) {
        return plan(source, entry, bp, args, Stock.NONE, out);
    }

    /** For a party's building: what the pins leave goes to what the party has, as the house line's does. */
    private static @Nullable BuildPlan plan(CommandSourceStack source, Entry entry, Blueprint bp, PlanArgs args,
                                            Stock stock, Diagnostics out) {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        BuildPlan plan = out.hasErrors() ? null
                : Planner.plan(bp, Blueprints.dictionary(), Placer.SUPPORT, args.pins(), args.variants(),
                        Chooser.stocked(stock, Chooser.random(random)), random, out);
        if (plan == null) {
            failed(source, entry, out);
        }
        return plan;
    }

    private static void failed(CommandSourceStack source, Entry entry, Diagnostics out) {
        Replies.fail(source, Component.translatable("autarkia.command.bp.plan.failed", entry.id()));
        for (Diagnostic d : out.list()) {
            send(source, indent(Component.literal(d.summary()).withStyle(d.isError() ? ChatFormatting.RED
                    : ChatFormatting.YELLOW)));
        }
    }

    /** {@code beds=two cellar=none 1=spruce}, as the pins would write it back. */
    private static Component bindings(BuildPlan plan) {
        if (plan.pins().isEmpty()) {
            return Component.translatable("autarkia.command.bp.bindings.none").withStyle(ChatFormatting.GRAY);
        }
        return Component.literal(plan.pins()).withStyle(ChatFormatting.AQUA);
    }

    /** The groups, what needs what, and what each variant adds to the base, counted as the facts count. */
    private static void variants(CommandSourceStack source, Blueprint bp, Dictionary dict, Facts base) {
        Variants variants = bp.variants();
        if (variants.isEmpty()) {
            return;
        }
        send(source, indent(Component.translatable("autarkia.command.bp.query.selections",
                variants.selections().size())));
        for (Variants.Group group : variants.groups()) {
            send(source, indent(Component.translatable("autarkia.command.bp.query.group", group.name(),
                    Component.translatable(group.required() ? "autarkia.command.bp.kind.required"
                            : "autarkia.command.bp.kind.optional"), String.join(", ", group.variants()))));
        }
        variants.needs().forEach((key, targets) -> send(source, indent(indent(Component.translatable(
                "autarkia.command.bp.query.variant_needs", key, String.join(", ", targets))
                .withStyle(ChatFormatting.GRAY)))));
        for (String key : variants.keys()) {
            Facts with = Facts.of(bp.compose(variants.with(key)), dict);
            MutableComponent changes = Component.empty();
            int[] deltas = {with.beds() - base.beds(), with.rooms() - base.rooms(), with.lights() - base.lights(),
                    with.stations().values().stream().mapToInt(Integer::intValue).sum()
                            - base.stations().values().stream().mapToInt(Integer::intValue).sum(),
                    with.placed() - base.placed()};
            String[] names = {"beds", "rooms", "lights", "stations", "blocks"};
            for (int i = 0; i < deltas.length; i++) {
                if (deltas[i] == 0) {
                    continue;
                }
                if (!changes.getSiblings().isEmpty()) {
                    changes.append(Component.literal(", "));
                }
                changes.append(Component.translatable("autarkia.command.bp.query.delta." + names[i],
                        (deltas[i] > 0 ? "+" : "") + deltas[i]));
            }
            if (changes.getSiblings().isEmpty()) {
                changes.append(Component.translatable("autarkia.command.bp.query.delta.none"));
            }
            send(source, indent(indent(Component.translatable("autarkia.command.bp.query.variant", key, changes))));
        }
    }

    // ── capture ─────────────────────────────────────────────────────────────────────────────

    /**
     * The holder's wand box to a file (capture spec): a new blueprint, the same one again with
     * {@code replace}, or a variant of it with {@code as group.variant}. The box is read a slice per
     * tick, and the reply comes when the file is written and loaded.
     */
    private static int capture(CommandContext<CommandSourceStack> ctx, String words) {
        CommandSourceStack source = ctx.getSource();
        if (!(source.getEntity() instanceof ServerPlayer player)) {
            Replies.fail(source, Component.translatable("autarkia.command.bp.capture.player"));
            return 0;
        }
        Identifier named = IdentifierArgument.getId(ctx, "name");
        String namespace = named.getNamespace().equals("minecraft") ? "autarkia" : named.getNamespace();
        String id = namespace + ":" + named.getPath();
        // A top-level file is autarkia's; anything else sits in a folder named for its namespace.
        Path file = namespace.equals("autarkia") && !named.getPath().contains("/")
                ? Blueprints.configDirectory().resolve(named.getPath() + ".bp")
                : Blueprints.configDirectory().resolve(namespace).resolve(named.getPath() + ".bp");
        Diagnostics out = new Diagnostics();
        Capture.Args args = Capture.Args.parse(words, out);
        if (out.hasErrors()) {
            Replies.fail(source, Component.translatable("autarkia.command.bp.capture.failed", id, out.list().stream()
                    .map(Diagnostic::message).collect(Collectors.joining("; "))));
            return 0;
        }
        Captures.Marked box = Captures.marked(player);
        if (box == null || !box.complete()) {
            Replies.fail(source, Component.translatable("autarkia.command.bp.capture.no_box"));
            return 0;
        }
        ServerLevel level = player.level();
        Integer ground = args.ground() != null ? args.ground() : box.ground() != null ? box.ground()
                : BoxReader.groundAround(level, box.first(), box.second()).stream().boxed().findFirst().orElse(null);
        if (ground == null) {
            Replies.fail(source, Component.translatable("autarkia.command.bp.capture.no_ground"));
            return 0;
        }
        Entry existing = Blueprints.library().get(id);
        boolean exists = Files.exists(file);
        if (args.group() != null) {
            if (!exists || existing == null) {
                Replies.fail(source, Component.translatable("autarkia.command.bp.capture.missing", id));
                return 0;
            }
            if (existing.compiled().blueprint() == null) {
                Replies.fail(source, Component.translatable("autarkia.command.bp.broken", id));
                return 0;
            }
        } else if (exists && !args.replace()) {
            Replies.fail(source, Component.translatable("autarkia.command.bp.capture.exists", id));
            return 0;
        } else if (exists && existing != null && existing.compiled().blueprint() != null
                && !existing.compiled().blueprint().variants().isEmpty()) {
            Replies.fail(source, Component.translatable("autarkia.command.bp.capture.has_variants", id));
            return 0;
        }
        Dictionary dict = Blueprints.dictionary();
        BoxReader.Started started = BoxReader.start(level, dict, box.first(), box.second(), ground);
        if (started.reader() == null) {
            Replies.fail(source, Component.translatable("autarkia.command.bp.capture.failed", id, started.reason()));
            return 0;
        }
        Capture.Headers headers;
        Blueprint was = existing == null ? null : existing.compiled().blueprint();
        if (exists && was != null && args.group() == null) {
            headers = new Capture.Headers(was.headers().name(), was.headers().author(), was.headers().version() + 1,
                    orientation(was), was.headers().flippable(), false);
        } else {
            String leaf = named.getPath().substring(named.getPath().lastIndexOf('/') + 1);
            headers = Capture.Headers.fresh(leaf.replace('_', ' '), player.getName().getString());
        }
        int layerZero = ground;
        send(source, Component.translatable("autarkia.command.bp.capture.started", id, started.reader().cellCount(),
                layerZero).withStyle(ChatFormatting.GRAY));
        Captures.read(started.reader(), reader -> {
            if (reader.failure() != null) {
                Replies.fail(source, Component.translatable("autarkia.command.bp.capture.failed", id,
                        reader.failure()));
                return;
            }
            Capture.Written written = args.group() != null
                    ? Capture.variant(existing.text(), was, reader.box(), args.group(), args.variant(), dict,
                            Placer.SUPPORT)
                    : Capture.write(reader.box(), dict, Placer.SUPPORT, headers);
            if (written.text() == null) {
                Replies.fail(source, Component.translatable("autarkia.command.bp.capture.failed", id,
                        String.join("; ", written.problems())));
                return;
            }
            try {
                Files.createDirectories(file.getParent());
                Files.writeString(file, written.text(), StandardCharsets.UTF_8);
            } catch (IOException e) {
                Replies.fail(source, Component.translatable("autarkia.command.bp.capture.failed", id,
                        String.valueOf(e.getMessage())));
                return;
            }
            Blueprints.reloadConfig();
            Captures.rememberGround(player, layerZero);
            Entry now = Blueprints.library().get(id);
            if (now == null) {
                return;
            }
            Replies.send(source, () -> Component.translatable("autarkia.command.bp.capture.done", id,
                    Blueprints.configDirectory().relativize(file).toString(), now.compiled().errors(),
                    now.compiled().reports()).withStyle(ChatFormatting.LIGHT_PURPLE), true);
            List<String> lines = DiagnosticText.lines(now.text());
            for (Diagnostic d : now.compiled().diagnostics()) {
                send(source, indent(Component.literal(d.summary())
                        .withStyle(d.isError() ? ChatFormatting.RED : ChatFormatting.YELLOW)));
                if (d.line() >= 1 && d.line() <= lines.size()) {
                    send(source, indent(indent(pointed(lines.get(d.line() - 1), d.column()))));
                }
            }
            // What bp query says, so a house with no way in shows before anyone places it.
            if (now.compiled().blueprint() != null) {
                query(source, now);
            }
            // Beside the file, so the designer's hand edits check offline against this game.
            try {
                Blueprints.exportDictionary();
            } catch (IOException e) {
                send(source, indent(Component.translatable("autarkia.command.bp.dictionary.failed",
                        String.valueOf(e.getMessage())).withStyle(ChatFormatting.YELLOW)));
            }
        });
        return 1;
    }

    private static String orientation(Blueprint bp) {
        if (bp.headers().orientation().containsAll(EnumSet.allOf(Facing.class))) {
            return "all";
        }
        return Arrays.stream(Facing.values()).filter(bp.headers().orientation()::contains).map(Facing::word)
                .collect(Collectors.joining(" "));
    }

    private static String brief(Set<String> ids) {
        return ids.stream().map(Ids::brief).sorted().collect(Collectors.joining(", "));
    }

    private static Component indent(Component line) {
        return Component.literal("  ").append(line);
    }

    private static void send(CommandSourceStack source, Component line) {
        Replies.send(source, () -> line);
    }
}
