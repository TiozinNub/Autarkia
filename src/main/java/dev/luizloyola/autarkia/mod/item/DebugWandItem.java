package dev.luizloyola.autarkia.mod.item;

import dev.luizloyola.autarkia.compat.Players;
import dev.luizloyola.autarkia.core.nav.Gait;
import dev.luizloyola.autarkia.core.person.PersonId;
import dev.luizloyola.autarkia.mod.command.PersonSelection;
import dev.luizloyola.autarkia.mod.debug.DebugLayer;
import dev.luizloyola.autarkia.mod.debug.DebugView;
import dev.luizloyola.autarkia.mod.entity.Person;
import dev.luizloyola.autarkia.mod.entity.Persons;
import java.util.EnumSet;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/**
 * A development tool that <em>selects</em> a {@link Person}: right-clicking one pins them to the
 * player's {@link PersonSelection} slot — the same slot {@code /autarkia select} uses. The pin is
 * per-player (not per stack) and lives on the server, mirrored to the client only for the
 * selection glow. Right-clicking a block then sends the pinned person walking there (see
 * {@link #useOn}). The wand itself is stateless — it carries no data.
 */
public class DebugWandItem extends Item {
    public DebugWandItem(Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResult useOn(UseOnContext context) {
        Level level = context.getLevel();
        // Mutate only on the server (the ServerPlayer cast implies server); the client returns SUCCESS
        // to predict the arm swing, mirroring the selection path in interactLivingEntity.
        if (level.isClientSide() || !(context.getPlayer() instanceof ServerPlayer player)) {
            return InteractionResult.SUCCESS;
        }
        PersonId selected = PersonSelection.pinned(player).orElse(null);
        if (selected == null) {
            Players.overlay(player, Component.translatable("item.autarkia.debug_wand.no_selection"));
            return InteractionResult.SUCCESS;
        }
        Person person = Persons.findLoaded(player.level().getServer(), selected);
        if (person == null) {
            Players.overlay(player, Component.translatable("item.autarkia.debug_wand.not_loaded"));
            return InteractionResult.SUCCESS;
        }
        // Stand on top of the clicked face.
        Vec3 target = Vec3.atBottomCenterOf(context.getClickedPos().relative(context.getClickedFace()));
        // Shift sends her at a run. The gait is advisory all the way down — the follower still
        // slows for careful ground and still takes a leap's run-up at full speed.
        boolean hurry = player.isSecondaryUseActive();
        person.navigateTo(target, hurry ? Gait.SPRINT : Gait.WALK);
        Players.overlay(player, Component.translatable(
                hurry ? "item.autarkia.debug_wand.running" : "item.autarkia.debug_wand.moving",
                person.getName(), (int) target.x, (int) target.y, (int) target.z));
        return InteractionResult.SUCCESS;
    }

    @Override
    public InteractionResult interactLivingEntity(
            ItemStack stack, Player player, LivingEntity target, InteractionHand hand) {
        if (!(target instanceof Person person)) {
            return InteractionResult.PASS;
        }
        if (player.isSecondaryUseActive()) {
            if (player instanceof ServerPlayer serverPlayer) {
                cycleDebugLayer(serverPlayer, person);
            }
            return InteractionResult.SUCCESS;
        }
        // The pin lands in the player's PersonSelection slot, which mirrors it to the client for the glow.
        if (player instanceof ServerPlayer serverPlayer) {
            PersonId id = person.getPersonId();
            if (id == null) {
                Players.overlay(serverPlayer,
                        Component.translatable("item.autarkia.debug_wand.no_identity"));
            } else {
                PersonSelection.pin(serverPlayer, id);
                Players.overlay(serverPlayer,
                        Component.translatable("item.autarkia.debug_wand.selected", person.getName()));
            }
        }
        return InteractionResult.SUCCESS;
    }

    /**
     * The wand's debug-view cycle: shift-click a Person to walk her debug layers one at a time —
     * path, brain, memory, peers, then off. It REPLACES the layer set rather than adding to it; the
     * command is the only way to have several up at once (decision: Luiz). Clicking also pins her,
     * so a shift-click on someone new both selects her and starts her cycle.
     */
    private static void cycleDebugLayer(ServerPlayer player, Person person) {
        PersonId id = person.getPersonId();
        if (id == null) {
            Players.overlay(player, Component.translatable("item.autarkia.debug_wand.no_identity"));
            return;
        }
        MinecraftServer server = player.level().getServer();
        boolean reselected = !id.equals(PersonSelection.pinned(player).orElse(null));
        if (reselected) {
            PersonSelection.pin(player, id);
        }
        // The cycle only CONTINUES from a single showing layer: with several up there is no
        // "current rung" to advance from, so the wand restarts at the first rather than guessing.
        EnumSet<DebugLayer> showing = DebugView.layers(server, player.getUUID());
        DebugLayer current = reselected || showing.size() != 1
                ? null
                : showing.iterator().next();
        DebugLayer next = DebugLayer.next(current).orElse(null);
        DebugView.replace(server, player.getUUID(),
                next == null ? EnumSet.noneOf(DebugLayer.class) : EnumSet.of(next));
        Players.overlay(player, next == null
                ? Component.translatable("item.autarkia.debug_wand.debug_off", person.getName())
                : Component.translatable("item.autarkia.debug_wand.debug_layer",
                        person.getName(), next.key()));
    }
}
