package dev.luizloyola.autarkia.mixin;

import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.goal.GoalSelector;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** A mob's target goals, for the one mob whose player hunt has to be joined rather than widened. */
@Mixin(Mob.class)
public interface MobTargetSelectorAccessor {

    @Accessor("targetSelector")
    GoalSelector autarkia$targetSelector();
}
