package dev.luizloyola.autarkia.compat.aggro;

import dev.luizloyola.autarkia.mod.entity.Person;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.targeting.TargetingConditions;
import net.minecraft.world.phys.AABB;

/**
 * A phantom's player hunt, with Persons in it. Vanilla's goal ({@code PhantomAttackPlayerTargetGoal})
 * scans a {@code List<Player>} every three seconds and takes the highest it may attack; this scans
 * players and Persons the same way, and takes the pick only when it is a Person — a player first is
 * left to vanilla's goal, so nothing changes where no Person is near.
 */
public final class PhantomHuntsPersons extends Goal {

    private final Mob phantom;
    private final TargetingConditions attackTargeting = TargetingConditions.forCombat().range(64.0);
    private int nextScanTick = reducedTickDelay(20);

    public PhantomHuntsPersons(Mob phantom) {
        this.phantom = phantom;
    }

    @Override
    public boolean canUse() {
        if (nextScanTick > 0) {
            nextScanTick--;
            return false;
        }
        nextScanTick = reducedTickDelay(60);
        if (!(phantom.level() instanceof ServerLevel level)) {
            return false;
        }
        AABB box = phantom.getBoundingBox().inflate(16.0, 64.0, 16.0);
        List<LivingEntity> near = new ArrayList<>(level.getNearbyPlayers(attackTargeting, phantom, box));
        for (Person person : level.getEntitiesOfClass(Person.class, box)) {
            if (attackTargeting.test(level, phantom, person)) {
                near.add(person);
            }
        }
        near.sort(Comparator.comparingDouble((LivingEntity entity) -> entity.getY()).reversed());
        for (LivingEntity candidate : near) {
            if (TargetingConditions.DEFAULT.test(level, phantom, candidate)) {
                if (!(candidate instanceof Person)) {
                    return false;
                }
                phantom.setTarget(candidate);
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean canContinueToUse() {
        LivingEntity target = phantom.getTarget();
        return target instanceof Person && phantom.level() instanceof ServerLevel level
                && TargetingConditions.DEFAULT.test(level, phantom, target);
    }
}
