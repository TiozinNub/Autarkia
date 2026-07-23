package dev.luizloyola.autarkia.mod.item;

import dev.luizloyola.autarkia.compat.Players;
import dev.luizloyola.autarkia.core.person.PersonId;
import dev.luizloyola.autarkia.mod.entity.ModEntities;
import dev.luizloyola.autarkia.mod.entity.Person;
import java.util.function.Consumer;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.TooltipDisplay;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/**
 * A development tool that <em>selects</em> a {@link Person}: right-clicking one binds that person's
 * {@link PersonId} onto the held stack ({@link ModComponents#SELECTED_PERSON}), persisted and
 * network-synced, so it survives reloads and shows in the tooltip. Right-clicking a block sends the
 * selected person walking there (see {@link #useOn}).
 */
public class DebugWandItem extends Item {
    public DebugWandItem(Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResult useOn(UseOnContext context) {
        Level level = context.getLevel();
        Player player = context.getPlayer();
        // Mutate only on the server; the client returns SUCCESS to predict the arm swing, mirroring
        // the selection path in interactLivingEntity.
        if (level.isClientSide() || player == null) {
            return InteractionResult.SUCCESS;
        }
        PersonId selected = context.getItemInHand().get(ModComponents.SELECTED_PERSON);
        if (selected == null) {
            Players.overlay(player, Component.translatable("item.autarkia.debug_wand.no_selection"));
            return InteractionResult.SUCCESS;
        }
        // Resolve the selection to a loaded entity: scan every loaded Person for the bound id. Fine
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
        // Mutate only on the server; the client returns SUCCESS to predict the arm swing (the
        // vanilla NameTagItem idiom). The updated stack (component and all) syncs back down.
        if (!player.level().isClientSide()) {
            PersonId id = person.getPersonId();
            if (id == null) {
                Players.overlay(player,
                        Component.translatable("item.autarkia.debug_wand.no_identity"));
            } else {
                // Write to the real held stack, not the `stack` parameter: in creative,
                // Player.interactOn swaps in a throwaway copy under instabuild, so mutating the
                // parameter is silently lost. getItemInHand returns the actual inventory stack.
                player.getItemInHand(hand).set(ModComponents.SELECTED_PERSON, id);
                Players.overlay(player,
                        Component.translatable("item.autarkia.debug_wand.selected", person.getName()));
            }
        }
        return InteractionResult.SUCCESS;
    }

    // appendHoverText is soft-deprecated (Mojang nudges toward TooltipProvider components), but it
    // stays the override point for custom item tooltips — and our component value is a core-layer
    // record that cannot implement the Minecraft TooltipProvider interface.
    @Override
    @SuppressWarnings("deprecation")
    public void appendHoverText(ItemStack stack, TooltipContext context, TooltipDisplay display,
            Consumer<Component> adder, TooltipFlag flag) {
        PersonId selected = stack.get(ModComponents.SELECTED_PERSON);
        if (selected != null) {
            adder.accept(Component.translatable("item.autarkia.debug_wand.bound", selected.toString())
                    .withStyle(ChatFormatting.GRAY));
        }
    }
}
