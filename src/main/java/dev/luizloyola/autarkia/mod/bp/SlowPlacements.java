package dev.luizloyola.autarkia.mod.bp;

import dev.luizloyola.anima.compat.Players;
import dev.luizloyola.anima.mod.command.Replies;
import dev.luizloyola.anima.mod.debug.CellOverlays;
import dev.luizloyola.anima.mod.net.CellOverlayPayload;
import dev.luizloyola.autarkia.compat.bp.Placer;
import dev.luizloyola.autarkia.core.bp.BuildPlan;
import dev.luizloyola.autarkia.core.bp.Placement;
import dev.luizloyola.autarkia.core.builder.BuildOrder.Placed;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

/**
 * {@code bp place … slow}: a plan placed a step at a time in the builder's proved order, so the
 * order can be watched. Authoring, like {@code bp place}: no record, no undo. Beside each block the
 * stand the proof had the builder place it from is painted.
 */
public final class SlowPlacements {

    private static final String OVERLAY = "autarkia:slow_place";
    private static final int STAND_STROKE = 0xFFFFB030;
    private static final int BLOCK_STROKE = 0xFF40FF80;
    private static final float STROKE_WIDTH = 2.5F;
    /** Players this near the site see the stands. */
    private static final double SHOWN_WITHIN = 96;

    private static final class Job {
        final CommandSourceStack source;
        final String id;
        final ServerLevel level;
        final BlockPos anchor;
        final BuildPlan plan;
        final Placement placement;
        final List<Placed> order;
        final int ticks;
        int next;
        int waited;

        Job(CommandSourceStack source, String id, BlockPos anchor, BuildPlan plan, Placement placement,
            List<Placed> order, int ticks) {
            this.source = source;
            this.id = id;
            this.level = source.getLevel();
            this.anchor = anchor;
            this.plan = plan;
            this.placement = placement;
            this.order = order;
            this.ticks = ticks;
        }
    }

    private static final List<Job> JOBS = new ArrayList<>();

    private SlowPlacements() {
    }

    public static void init() {
        ServerTickEvents.END_SERVER_TICK.register(SlowPlacements::tick);
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> JOBS.clear());
    }

    /** Places {@code order} a step every {@code ticks} ticks, starting next tick. */
    public static void start(CommandSourceStack source, String id, BlockPos anchor, BuildPlan plan,
                             Placement placement, List<Placed> order, int ticks) {
        if (!order.isEmpty()) {
            JOBS.add(new Job(source, id, anchor, plan, placement, order, ticks));
        }
    }

    /** Ends every slow placement where it stands; returns how many were running. */
    public static int stop() {
        int running = JOBS.size();
        JOBS.forEach(SlowPlacements::clear);
        JOBS.clear();
        return running;
    }

    private static void tick(MinecraftServer server) {
        for (Iterator<Job> it = JOBS.iterator(); it.hasNext(); ) {
            Job job = it.next();
            if (++job.waited < job.ticks) {
                continue;
            }
            job.waited = 0;
            Placed placed = job.order.get(job.next);
            if (!Placer.step(job.level, job.anchor, job.plan, job.placement, placed.step())) {
                it.remove();
                clear(job);
                Replies.fail(job.source, Component.translatable("autarkia.command.bp.place.slow.unloaded", job.id));
                continue;
            }
            job.next++;
            paint(job, placed);
            if (job.source.getEntity() instanceof ServerPlayer player) {
                String section = placed.step().section().name().toLowerCase(Locale.ROOT);
                Players.overlay(player, Component.translatable("autarkia.command.bp.place.slow.progress",
                        Component.translatable("autarkia.builder.section." + section), job.next, job.order.size()));
            }
            if (job.next == job.order.size()) {
                it.remove();
                Replies.send(job.source, () -> Component.translatable("autarkia.command.bp.place.slow.done", job.id,
                        job.order.size()), true);
            }
        }
    }

    /** The block just placed, and the two cells a body stood in to place it. */
    private static void paint(Job job, Placed placed) {
        BlockPos stand = Placer.at(job.anchor, job.plan, job.placement, placed.stand());
        List<BlockPos> block = placed.step().cells().stream()
                .map(cell -> Placer.at(job.anchor, job.plan, job.placement, cell)).toList();
        CellOverlayPayload frame = new CellOverlayPayload(OVERLAY, Math.max(job.ticks * 3, 20),
                List.of(new CellOverlayPayload.Group(BLOCK_STROKE, STROKE_WIDTH, 0, true, block)), List.of(),
                List.of(new CellOverlayPayload.BoxGroup(STAND_STROKE, STROKE_WIDTH, 0, true,
                        List.of(new CellOverlayPayload.Box(stand, stand.above())))), List.of());
        for (ServerPlayer player : job.level.players()) {
            if (player.blockPosition().closerThan(job.anchor, SHOWN_WITHIN)) {
                CellOverlays.show(player, frame);
            }
        }
    }

    private static void clear(Job job) {
        for (ServerPlayer player : job.level.players()) {
            CellOverlays.clear(player, OVERLAY);
        }
    }
}
