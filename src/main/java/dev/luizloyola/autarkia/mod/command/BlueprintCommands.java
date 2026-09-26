package dev.luizloyola.autarkia.mod.command;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import dev.luizloyola.anima.mod.command.Replies;
import dev.luizloyola.autarkia.compat.bp.Placer;
import dev.luizloyola.autarkia.core.bp.Blueprint;
import dev.luizloyola.autarkia.core.bp.Blueprint.Facing;
import dev.luizloyola.autarkia.core.bp.Blueprint.SlotInfo;
import dev.luizloyola.autarkia.core.bp.Blueprint.SlotKind;
import dev.luizloyola.autarkia.core.bp.BpCompiler.Compiled;
import dev.luizloyola.autarkia.core.bp.BpText;
import dev.luizloyola.autarkia.core.bp.BuildPlan;
import dev.luizloyola.autarkia.core.bp.BuildPlan.BillLine;
import dev.luizloyola.autarkia.core.bp.Chooser;
import dev.luizloyola.autarkia.core.bp.Diagnostic;
import dev.luizloyola.autarkia.core.bp.DiagnosticText;
import dev.luizloyola.autarkia.core.bp.Diagnostics;
import dev.luizloyola.autarkia.core.bp.Dictionary;
import dev.luizloyola.autarkia.core.bp.Facts;
import dev.luizloyola.autarkia.core.bp.Ids;
import dev.luizloyola.autarkia.core.bp.PlanArgs;
import dev.luizloyola.autarkia.core.bp.Placement;
import dev.luizloyola.autarkia.core.bp.Planner;
import dev.luizloyola.autarkia.core.direction.Node;
import dev.luizloyola.autarkia.core.direction.Tree;
import dev.luizloyola.autarkia.mod.bp.Blueprints;
import dev.luizloyola.autarkia.mod.bp.Blueprints.Entry;
import dev.luizloyola.autarkia.mod.direction.Directions;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
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
        send(source, grid("layer " + layer, BpText.rows(bp, layer)));
        List<String> overlay = BpText.overlayRows(bp, layer);
        if (!overlay.isEmpty()) {
            send(source, grid("node layer " + layer, overlay));
        }
        // The cells only: a row's node ids would otherwise match the glyphs their letters spell.
        String cells = BpText.rows(bp, layer).stream().map(row -> row.substring(0, bp.width()))
                .collect(Collectors.joining());
        List<String> used = new ArrayList<>();
        bp.legend().values().forEach(e -> {
            if (cells.indexOf(e.glyph()) >= 0) {
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
        if (args.facing() != null || args.flip()) {
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
        Placer.Result result = Placer.place(source.getLevel(), anchor, plan, placement);
        switch (result.status()) {
            case UNLOADED -> Replies.fail(source, Component.translatable("autarkia.command.bp.place.unloaded"));
            case OUTSIDE_WORLD -> Replies.fail(source, Component.translatable("autarkia.command.bp.place.outside",
                    result.detail()));
            case UNKNOWN_STATE -> Replies.fail(source, Component.translatable("autarkia.command.bp.place.unknown",
                    result.detail()));
            case PLACED -> {
                MutableComponent line = Component.translatable("autarkia.command.bp.place.done", entry.id(),
                        plan.version(), anchor.getX(), anchor.getY(), anchor.getZ(), placement.north().word(),
                        result.blocks(), result.cleared(), result.filled());
                if (placement.flip()) {
                    line.append(Component.translatable("autarkia.command.bp.place.flipped"));
                }
                line.append(Component.literal(" — ")).append(bindings(plan));
                Replies.send(source, () -> line, true);
            }
        }
        return result.status() == Placer.Status.PLACED ? 1 : 0;
    }

    /** A command's chooser is random (reader spec): the bindings it prints are what to pin for a repeat. */
    private static @Nullable BuildPlan plan(CommandSourceStack source, Entry entry, Blueprint bp, PlanArgs args,
                                            Diagnostics out) {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        BuildPlan plan = out.hasErrors() ? null
                : Planner.plan(bp, Blueprints.dictionary(), Placer.SUPPORT, args.pins(), Chooser.random(random),
                        random, out);
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

    /** {@code 1=spruce 2=red}, as a pin would write it back. */
    private static Component bindings(BuildPlan plan) {
        if (plan.bindings().isEmpty()) {
            return Component.translatable("autarkia.command.bp.bindings.none").withStyle(ChatFormatting.GRAY);
        }
        return Component.literal(plan.bindings().entrySet().stream().map(e -> e.getKey() + "=" + e.getValue())
                .collect(Collectors.joining(" "))).withStyle(ChatFormatting.AQUA);
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
