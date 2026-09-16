package dev.luizloyola.autarkia.mixin;

import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * The swing clock's private door, one per side of 26.3. Before it the clock is
 * {@code protected updateSwingTime()}, which only {@code Player.aiStep} calls; from it the whole
 * swing is one {@code SwingState} that {@code baseTick} advances for every living body, and the
 * door is to that object, so a stand-in can share the body's rather than copy it.
 */
@Mixin(LivingEntity.class)
public interface LivingEntitySwingAccess {

    //? if >=26.3 {
    /*@Accessor("swingState")
    LivingEntity.SwingState autarkia$swingState();

    @Mutable
    @Accessor("swingState")
    void autarkia$swingState(LivingEntity.SwingState state);
    *///?} else {
    @Invoker("updateSwingTime")
    void autarkia$updateSwingTime();
    //?}
}
