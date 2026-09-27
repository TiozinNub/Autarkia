package dev.luizloyola.autarkia.mod.bp;

import dev.luizloyola.anima.compat.Players;
import dev.luizloyola.anima.mod.debug.CellOverlays;
import dev.luizloyola.anima.mod.net.CellOverlayPayload;
import dev.luizloyola.autarkia.compat.bp.BoxReader;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.context.UseOnContext;
import org.jspecify.annotations.Nullable;

/**
 * The blueprint wand and what it marks (capture spec, 2026-09-27): each player's box, painted while
 * the wand is on the hotbar, and the captures reading the world a slice per tick.
 */
public final class Captures {

    private static final String OVERLAY = "autarkia:blueprint_wand";
    /** A box that has not changed is sent again this often, only so the client's frame does not expire. */
    private static final int REFRESH_TICKS = 10;
    private static final int PAINT_TTL_TICKS = 30;
    private static final int BOX_STROKE = 0xFF40E0FF;
    private static final int GROUND_STROKE = 0xFF60FF60;
    private static final float STROKE_WIDTH = 2.5F;
    /** Cells read per tick: a 40³ house in one, the backstop's 128³ in a few seconds. */
    private static final int CELLS_PER_TICK = 65_536;

    /** Right-click a block for a corner, twice; sneak and right-click to clear. */
    public static final Item WAND = register("blueprint_wand", Wand::new);

    /**
     * A player's marked box.
     *
     * @param ground layer 0 once a capture has used it, so a variant is read on the base's ground
     */
    public record Marked(@Nullable BlockPos first, @Nullable BlockPos second, @Nullable Integer ground) {
        public boolean complete() {
            return first != null && second != null;
        }
    }

    private record Job(BoxReader reader, Consumer<BoxReader> done) {
    }

    /** What a player was last sent, so a change goes out the tick it happens and nothing else is redrawn. */
    private record Shown(Marked box, CellOverlayPayload frame) {
    }

    private static final Map<UUID, Marked> MARKED = new ConcurrentHashMap<>();
    private static final Map<UUID, Shown> SHOWN = new ConcurrentHashMap<>();
    private static final List<Job> JOBS = new ArrayList<>();

    private Captures() {
    }

    public static void init() {
        ServerTickEvents.END_SERVER_TICK.register(Captures::tick);
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
            MARKED.clear();
            SHOWN.clear();
            JOBS.clear();
        });
    }

    public static @Nullable Marked marked(ServerPlayer player) {
        return MARKED.get(player.getUUID());
    }

    /** Keeps the ground a capture used with the box, for the variants captured over it next. */
    public static void rememberGround(ServerPlayer player, int ground) {
        MARKED.computeIfPresent(player.getUUID(), (id, box) -> new Marked(box.first(), box.second(), ground));
    }

    /** Reads the box a slice per tick; {@code done} runs on the server thread once it is read or has failed. */
    public static void read(BoxReader reader, Consumer<BoxReader> done) {
        JOBS.add(new Job(reader, done));
    }

    private static void tick(MinecraftServer server) {
        for (Iterator<Job> it = JOBS.iterator(); it.hasNext(); ) {
            Job job = it.next();
            if (job.reader().step(CELLS_PER_TICK)) {
                it.remove();
                job.done().accept(job.reader());
            }
        }
        // Every tick, so a corner, a clear or the wand leaving the hotbar shows at once; a box that
        // has not changed is only sent again to keep it alive, and its ground is never read twice.
        boolean refresh = server.getTickCount() % REFRESH_TICKS == 0;
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            UUID id = player.getUUID();
            Marked box = MARKED.get(id);
            if (carried(player) && box != null && box.first() != null) {
                Shown was = SHOWN.get(id);
                if (was == null || !was.box().equals(box)) {
                    Shown now = new Shown(box, paint(player.level(), box));
                    SHOWN.put(id, now);
                    CellOverlays.show(player, now.frame());
                } else if (refresh) {
                    CellOverlays.show(player, was.frame());
                }
            } else if (SHOWN.remove(id) != null) {
                CellOverlays.clear(player, OVERLAY);
            }
        }
    }

    /** On the hotbar or in the off hand: within a key of the hand, so the box stays in sight while building. */
    private static boolean carried(ServerPlayer player) {
        if (player.getOffhandItem().is(WAND)) {
            return true;
        }
        for (int slot = 0; slot < Inventory.getSelectionSize(); slot++) {
            if (player.getInventory().getItem(slot).is(WAND)) {
                return true;
            }
        }
        return false;
    }

    /** The box's outline, and its ground layer in another colour when the ground can be told. */
    private static CellOverlayPayload paint(ServerLevel level, Marked box) {
        BlockPos a = box.first();
        BlockPos b = box.second() != null ? box.second() : a;
        BlockPos min = new BlockPos(Math.min(a.getX(), b.getX()), Math.min(a.getY(), b.getY()),
                Math.min(a.getZ(), b.getZ()));
        BlockPos max = new BlockPos(Math.max(a.getX(), b.getX()), Math.max(a.getY(), b.getY()),
                Math.max(a.getZ(), b.getZ()));
        List<CellOverlayPayload.BoxGroup> groups = new ArrayList<>();
        groups.add(new CellOverlayPayload.BoxGroup(BOX_STROKE, STROKE_WIDTH, 0, true,
                List.of(new CellOverlayPayload.Box(min, max))));
        OptionalInt ground = box.ground() != null ? OptionalInt.of(box.ground())
                : box.complete() ? BoxReader.groundAround(level, min, max) : OptionalInt.empty();
        if (ground.isPresent() && ground.getAsInt() >= min.getY() && ground.getAsInt() <= max.getY()) {
            groups.add(new CellOverlayPayload.BoxGroup(GROUND_STROKE, STROKE_WIDTH, 0, true, List.of(
                    new CellOverlayPayload.Box(new BlockPos(min.getX(), ground.getAsInt(), min.getZ()),
                            new BlockPos(max.getX(), ground.getAsInt(), max.getZ())))));
        }
        return new CellOverlayPayload(OVERLAY, PAINT_TTL_TICKS, List.of(), List.of(), groups, List.of());
    }

    private static Item register(String name, java.util.function.Function<Item.Properties, Item> factory) {
        Identifier id = Identifier.fromNamespaceAndPath("autarkia", name);
        ResourceKey<Item> key = ResourceKey.create(Registries.ITEM, id);
        return Registry.register(BuiltInRegistries.ITEM, id, factory.apply(new Item.Properties().setId(key)
                .stacksTo(1)));
    }

    /** The wand itself: stateless, the box is kept per player above. */
    private static final class Wand extends Item {

        Wand(Properties properties) {
            super(properties);
        }

        @Override
        public InteractionResult useOn(UseOnContext context) {
            if (context.getLevel().isClientSide() || !(context.getPlayer() instanceof ServerPlayer player)) {
                return InteractionResult.SUCCESS;
            }
            UUID id = player.getUUID();
            if (player.isSecondaryUseActive()) {
                MARKED.remove(id);
                Players.overlay(player, Component.translatable("autarkia.wand.cleared"));
                return InteractionResult.SUCCESS;
            }
            BlockPos at = context.getClickedPos().immutable();
            Marked was = MARKED.get(id);
            Marked now = was == null || was.complete() ? new Marked(at, null, null) : new Marked(was.first(), at, null);
            MARKED.put(id, now);
            if (!now.complete()) {
                Players.overlay(player, Component.translatable("autarkia.wand.first", at.getX(), at.getY(), at.getZ()));
            } else {
                BlockPos a = now.first();
                Players.overlay(player, Component.translatable("autarkia.wand.box",
                        Math.abs(a.getX() - at.getX()) + 1, Math.abs(a.getZ() - at.getZ()) + 1,
                        Math.abs(a.getY() - at.getY()) + 1));
            }
            return InteractionResult.SUCCESS;
        }
    }
}
