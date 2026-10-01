package dev.luizloyola.autarkia.mod.command;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import dev.luizloyola.anima.compat.terrain.NaturalGroundReader;
import dev.luizloyola.anima.core.terrain.NaturalGround;
import dev.luizloyola.anima.mod.command.Replies;
import dev.luizloyola.autarkia.core.earthwork.FlattenPlan;
import dev.luizloyola.autarkia.mod.debug.FlattenPlanViewer;
import java.util.EnumMap;
import java.util.Map;
import java.util.OptionalInt;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import org.jspecify.annotations.Nullable;

/**
 * {@code flatten plan <from> <to> [tolerance] [y <Y>]}: scan the natural ground, plan the flatten,
 * paint it and say what it would take — changing nothing. {@code board party post flatten} takes
 * the same arguments and posts what this shows.
 */
public final class FlattenCommands {

    /** The radius each column's ground is averaged over: a guess until flown on real ground. */
    static final int SMOOTHING = 2;
    /** The widest the eased ring outside the area may grow before the plan calls it a hillside. */
    static final int MAX_RING = 8;
    /** A side longer than this is refused: the scan, the plan and the offers all grow with the area. */
    static final int MAX_SIDE = 128;
    static final int DEFAULT_TOLERANCE = 1;
    static final int MAX_TOLERANCE = 16;

    private FlattenCommands() {
    }

    /** What a scan and a plan of one area came to. */
    record Planned(FlattenPlan plan, NaturalGround scan, BlockPos min, BlockPos max, int tolerance) {
    }

    /** The arguments shared by {@code plan} and {@code post flatten}, each leaf calling {@code then}. */
    interface Leaf {
        int run(CommandContext<CommandSourceStack> ctx, BlockPos from, BlockPos to, int tolerance,
                OptionalInt y) throws CommandSyntaxException;
    }

    /** {@code <from> <to> [tolerance] [y <Y>]}, each form ending in {@code leaf}. */
    static com.mojang.brigadier.builder.RequiredArgumentBuilder<CommandSourceStack, ?> area(Leaf leaf) {
        return Commands.argument("from", BlockPosArgument.blockPos())
                .then(Commands.argument("to", BlockPosArgument.blockPos())
                        .executes(ctx -> leaf.run(ctx, corner(ctx, "from"), corner(ctx, "to"),
                                DEFAULT_TOLERANCE, OptionalInt.empty()))
                        .then(Commands.argument("tolerance", IntegerArgumentType.integer(0, MAX_TOLERANCE))
                                .executes(ctx -> leaf.run(ctx, corner(ctx, "from"), corner(ctx, "to"),
                                        IntegerArgumentType.getInteger(ctx, "tolerance"), OptionalInt.empty()))
                                .then(Commands.literal("y")
                                        .then(Commands.argument("y", IntegerArgumentType.integer())
                                                .executes(ctx -> leaf.run(ctx, corner(ctx, "from"),
                                                        corner(ctx, "to"),
                                                        IntegerArgumentType.getInteger(ctx, "tolerance"),
                                                        OptionalInt.of(IntegerArgumentType.getInteger(ctx, "y"))))))));
    }

    private static BlockPos corner(CommandContext<CommandSourceStack> ctx, String name)
            throws CommandSyntaxException {
        return BlockPosArgument.getLoadedBlockPos(ctx, name);
    }

    /** {@code /autarkia flatten plan …} — no subject: it reads the world, not a settler. */
    public static LiteralArgumentBuilder<CommandSourceStack> flatten() {
        return Commands.literal("flatten")
                .then(Commands.literal("plan").then(area(FlattenCommands::showPlan)));
    }

    private static int showPlan(CommandContext<CommandSourceStack> ctx, BlockPos from, BlockPos to,
                                int tolerance, OptionalInt y) {
        CommandSourceStack source = ctx.getSource();
        Planned planned = plan(source, source.getLevel(), from, to, tolerance, y);
        if (planned == null) {
            return 0;
        }
        ServerPlayer player = source.getPlayer();
        if (player != null) {
            FlattenPlanViewer.show(player, planned.plan(), planned.scan(), planned.min(), planned.max());
        }
        report(source, planned);
        return planned.plan().refused() ? 0 : 1;
    }

    /**
     * Scans and plans the area between two corners, or says why not and answers null. A refused
     * plan is answered, not null: what refused it is worth painting.
     */
    static @Nullable Planned plan(CommandSourceStack source, ServerLevel level, BlockPos from, BlockPos to,
                                  int tolerance, OptionalInt y) {
        int minX = Math.min(from.getX(), to.getX());
        int minZ = Math.min(from.getZ(), to.getZ());
        int maxX = Math.max(from.getX(), to.getX());
        int maxZ = Math.max(from.getZ(), to.getZ());
        int wide = maxX - minX + 1;
        int deep = maxZ - minZ + 1;
        if (wide > MAX_SIDE || deep > MAX_SIDE) {
            Replies.fail(source, Component.translatable("autarkia.command.flatten.too_big", wide, deep,
                    MAX_SIDE));
            return null;
        }
        NaturalGround scan = NaturalGroundReader.read(level, minX - MAX_RING, minZ - MAX_RING,
                maxX + MAX_RING, maxZ + MAX_RING);
        FlattenPlan plan = FlattenPlan.of(scan, minX, minZ, maxX, maxZ,
                new FlattenPlan.Rules(tolerance, y, SMOOTHING, MAX_RING));
        int level0 = plan.refused() ? Math.min(from.getY(), to.getY()) : plan.y();
        return new Planned(plan, scan, new BlockPos(minX, level0, minZ), new BlockPos(maxX, level0, maxZ),
                tolerance);
    }

    /** The plan in words: its height and why, the work, and what is short; or why it is refused. */
    static void report(CommandSourceStack source, Planned planned) {
        FlattenPlan plan = planned.plan();
        if (plan.refused()) {
            Replies.fail(source, Component.translatable("autarkia.command.flatten.refused",
                    refusals(plan)));
            return;
        }
        Component why = Component.translatable("autarkia.command.flatten.why."
                + plan.why().name().toLowerCase(java.util.Locale.ROOT));
        Replies.send(source, () -> Component.translatable("autarkia.command.flatten.plan",
                plan.y(), planned.tolerance(), why, plan.median(), plan.cut(), plan.fill(),
                plan.columns().size(), plan.skipped()).withStyle(ChatFormatting.LIGHT_PURPLE));
        int gap = plan.fill() - plan.cut();
        if (gap > 0) {
            Replies.send(source, () -> Component.translatable("autarkia.command.flatten.short", gap)
                    .withStyle(ChatFormatting.YELLOW));
        } else if (gap < 0) {
            Replies.send(source, () -> Component.translatable("autarkia.command.flatten.surplus", -gap)
                    .withStyle(ChatFormatting.GRAY));
        }
    }

    /** {@code 3 columns with trees on them, 1 of hillside}: one clause per kind of refusal. */
    private static Component refusals(FlattenPlan plan) {
        Map<FlattenPlan.Refusal, Integer> counts = new EnumMap<>(FlattenPlan.Refusal.class);
        for (FlattenPlan.Refused r : plan.refusals()) {
            counts.merge(r.why(), 1, Integer::sum);
        }
        MutableComponent line = Component.empty();
        boolean first = true;
        for (Map.Entry<FlattenPlan.Refusal, Integer> entry : counts.entrySet()) {
            if (!first) {
                line.append(Component.translatable("autarkia.command.flatten.and"));
            }
            line.append(Component.translatable("autarkia.command.flatten.refusal."
                    + entry.getKey().name().toLowerCase(java.util.Locale.ROOT), entry.getValue()));
            first = false;
        }
        return line;
    }
}
