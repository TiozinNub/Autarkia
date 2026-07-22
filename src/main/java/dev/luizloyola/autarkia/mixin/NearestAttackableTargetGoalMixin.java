package dev.luizloyola.autarkia.mixin;

import dev.luizloyola.autarkia.mod.entity.Person;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntitySelector;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.target.NearestAttackableTargetGoal;
import net.minecraft.world.entity.ai.targeting.TargetingConditions;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Makes hostile mobs treat a {@link Person} like a player.
 *
 * <p>A Person is an {@code Avatar} (a plain {@code LivingEntity}), so vanilla hostiles targeting
 * {@code Player.class} never pick it. Rather than inject a goal into every hostile type, this
 * widens the routine they all share: after {@link NearestAttackableTargetGoal#findTarget()} picks
 * the nearest player, a player-hunting goal re-runs the search over players <em>and</em> Persons
 * and keeps the closest. The Person is a passive victim that does not retaliate.
 *
 * <p>Port of outlanders-old's {@code ActiveTargetGoalMixin}. Ordinary Mixin + public surface only —
 * Sinytra Connector safe. The owning mob comes from {@link TargetGoalAccessor}:
 * {@code TargetGoal.mob} is an inherited field.
 */
@Mixin(NearestAttackableTargetGoal.class)
public abstract class NearestAttackableTargetGoalMixin {

    @Shadow @Final protected Class<? extends LivingEntity> targetType;
    @Shadow protected LivingEntity target;
    @Shadow @Final protected TargetingConditions targetConditions;

    @Inject(method = "findTarget", at = @At("RETURN"))
    private void autarkia$alsoTargetPersons(CallbackInfo ci) {
        // Only augment the goals that hunt players; leave villager/animal/etc. target goals alone.
        if (this.targetType != Player.class && this.targetType != ServerPlayer.class) {
            return;
        }
        Mob mob = ((TargetGoalAccessor) this).autarkia$getMob();
        if (!(mob.level() instanceof ServerLevel level)) {
            return;
        }

        // Same search box vanilla uses for its non-player branch:
        // getTargetSearchArea(getFollowDistance()) == boundingBox.inflate(followRange).
        double followRange = mob.getAttributeValue(Attributes.FOLLOW_RANGE);

        // The goal's own range-configured TargetingConditions (line of sight, follow range,
        // visibility) then decide the pick from players + Persons, exactly as vanilla does for
        // players.
        List<LivingEntity> candidates = new ArrayList<>(level.players());
        candidates.addAll(level.getEntitiesOfClass(Person.class,
                mob.getBoundingBox().inflate(followRange),
                EntitySelector.LIVING_ENTITY_STILL_ALIVE));

        this.target = level.getNearestEntity(candidates, this.targetConditions, mob,
                mob.getX(), mob.getEyeY(), mob.getZ());
    }
}
