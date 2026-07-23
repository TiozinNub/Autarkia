package dev.luizloyola.autarkia.mod.item;

import dev.luizloyola.autarkia.compat.Players;
import dev.luizloyola.autarkia.core.person.PersonId;
import dev.luizloyola.autarkia.mod.command.PersonSelection;
import dev.luizloyola.autarkia.mod.entity.ModEntities;
import dev.luizloyola.autarkia.mod.entity.Person;
import net.minecraft.network.chat.Component;
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
        // Resolve the selection to a loaded entity: scan every loaded Person for the pinned id. Fine
        // for a debug tool; the real command path will hold a direct handle.
        Person person = ((ServerLevel) level)
                .getEntities(ModEntities.PERSON, p -> selected.equals(p.getPersonId()))
                .stream().findFirst().orElse(null);
        if (person == null) {
            Players.overlay(player, Component.translatable("item.autarkia.debug_wand.not_loaded"));
            return InteractionResult.SUCCESS;
        }
        // Stand on top of the clicked face.
        Vec3 target = Vec3.atBottomCenterOf(context.getClickedPos().relative(context.getClickedFace()));
        person.navigateTo(target);
        Players.overlay(player, Component.translatable("item.autarkia.debug_wand.moving",
                person.getName(), (int) target.x, (int) target.y, (int) target.z));
        return InteractionResult.SUCCESS;
    }

    @Override
    public InteractionResult interactLivingEntity(
            ItemStack stack, Player player, LivingEntity target, InteractionHand hand) {
        if (!(target instanceof Person person)) {
            return InteractionResult.PASS;
        }
        // Shift-right-click drives the temporary debug walker (locomotion tuning); a plain click
        // selects. Mutate only on the server, mirroring the selection path below.
        if (player.isSecondaryUseActive()) {
            if (!player.level().isClientSide()) {
                boolean walking = person.toggleDebugWalk(player.getYRot());
                Players.overlay(player, Component.translatable(
                        walking ? "item.autarkia.debug_wand.walk_on"
                                : "item.autarkia.debug_wand.walk_off",
                        person.getName()));
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
}
