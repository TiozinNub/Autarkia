package dev.luizloyola.autarkia.mixin;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType;
import dev.luizloyola.autarkia.mod.entity.ModEntities;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.Holder;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.commands.SummonCommand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Rejects {@code /summon autarkia:person} with a chat error, steering admins to
 * {@code /autarkia person spawn [name]}, which registers an identity in the world-scoped
 * {@code PersonDirectory} <em>before</em> the entity enters the world; a bare {@code /summon}
 * leaves an entity that silently mints an anonymous, random identity on its first tick.
 *
 * <p>Not {@code noSummon()}, which would also hide the type from registry introspection and other
 * compat paths. {@link SummonCommand#createEntity} is the single funnel every variant passes
 * through and carries the {@link CommandSourceStack}, so throwing here surfaces a normal red
 * command error; the mod's own spawn path uses {@code EntityType.create(...)} and is unaffected.
 *
 * <p>Ordinary Mixin + public surface only — Sinytra Connector safe. Version-specific by nature (it
 * pins {@code createEntity}'s 26.1 signature), hence the mixin/compat layer.
 */
@Mixin(SummonCommand.class)
public abstract class SummonCommandMixin {
    private static final SimpleCommandExceptionType AUTARKIA$NO_SUMMON = new SimpleCommandExceptionType(
            Component.literal("autarkia:person can't be created with /summon — "
                    + "use /autarkia person spawn [name] so it gets a registered identity."));

    @Inject(method = "createEntity", at = @At("HEAD"))
    private static void autarkia$blockPersonSummon(
            CommandSourceStack source, Holder.Reference<EntityType<?>> type, Vec3 pos,
            CompoundTag nbt, boolean initialize, CallbackInfoReturnable<Entity> cir)
            throws CommandSyntaxException {
        if (type.value() == ModEntities.PERSON) {
            throw AUTARKIA$NO_SUMMON.create();
        }
    }
}
