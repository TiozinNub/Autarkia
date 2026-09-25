package dev.luizloyola.autarkia.mod.command;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import dev.luizloyola.anima.mod.command.Replies;
import dev.luizloyola.autarkia.core.bp.Blueprint;
import dev.luizloyola.autarkia.core.bp.Blueprint.Facing;
import dev.luizloyola.autarkia.core.bp.Blueprint.SlotInfo;
import dev.luizloyola.autarkia.core.bp.Blueprint.SlotKind;
import dev.luizloyola.autarkia.core.bp.BpCompiler.Compiled;
import dev.luizloyola.autarkia.core.bp.BpText;
import dev.luizloyola.autarkia.core.bp.Diagnostic;
import dev.luizloyola.autarkia.core.bp.DiagnosticText;
import dev.luizloyola.autarkia.core.bp.Dictionary;
import dev.luizloyola.autarkia.core.bp.Facts;
import dev.luizloyola.autarkia.core.bp.Ids;
import dev.luizloyola.autarkia.core.direction.Node;
import dev.luizloyola.autarkia.core.direction.Tree;
import dev.luizloyola.autarkia.mod.bp.Blueprints;
import dev.luizloyola.autarkia.mod.bp.Blueprints.Entry;
import dev.luizloyola.autarkia.mod.direction.Directions;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

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
                .then(Commands.literal("show").then(id(BlueprintCommands::show)))
                .then(Commands.literal("query").then(id(BlueprintCommands::query)))
                .then(Commands.literal("reload").executes(BlueprintCommands::reload))
                .then(Commands.literal("dictionary").executes(BlueprintCommands::dictionary));
    }

    private interface IdCommand {
        int run(CommandSourceStack source, Entry entry);
    }

    /** Greedy, so a namespaced id needs no quotes; its path alone will do when it is unique. */
    private static com.mojang.brigadier.builder.RequiredArgumentBuilder<CommandSourceStack, String> id(
            IdCommand command) {
        return Commands.argument("id", StringArgumentType.greedyString()).suggests(IDS)
                .executes(ctx -> withEntry(ctx, command));
    }

    private static int withEntry(CommandContext<CommandSourceStack> ctx, IdCommand command) {
        String id = StringArgumentType.getString(ctx, "id").trim();
        Optional<Entry> entry = Blueprints.find(id);
        if (entry.isEmpty()) {
            Replies.fail(ctx.getSource(), Component.translatable("autarkia.command.bp.unknown", id));
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

    private static int show(CommandSourceStack source, Entry entry) {
        Blueprint bp = entry.compiled().blueprint();
        if (bp == null) {
            Replies.fail(source, Component.translatable("autarkia.command.bp.broken", entry.id()));
            return 0;
        }
        send(source, Component.translatable("autarkia.command.bp.show.header", entry.id())
                .withStyle(ChatFormatting.LIGHT_PURPLE));
        for (String line : BpText.render(bp).split("\n")) {
            send(source, Component.literal(line.isEmpty() ? " " : line).withStyle(ChatFormatting.GRAY));
        }
        return 1;
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
