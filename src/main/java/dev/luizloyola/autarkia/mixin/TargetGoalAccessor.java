package dev.luizloyola.autarkia.mixin;

import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.goal.target.TargetGoal;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Exposes {@code TargetGoal}'s {@code protected final mob} field to
 * {@link NearestAttackableTargetGoalMixin}. Shadowing an inherited field from a mixin that does
 * not extend the declaring class fails to resolve, so we reach it via interface injection instead
 * (the same approach outlanders-old used).
 */
@Mixin(TargetGoal.class)
public interface TargetGoalAccessor {

    @Accessor("mob")
    Mob autarkia$getMob();
}
