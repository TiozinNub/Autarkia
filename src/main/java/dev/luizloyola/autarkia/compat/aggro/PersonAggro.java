package dev.luizloyola.autarkia.compat.aggro;

import dev.luizloyola.autarkia.mod.entity.Person;
import java.util.Optional;
import java.util.function.Predicate;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.sensing.Sensor;

/**
 * A Person as a brain-driven mob's unprovoked target, beside the player vanilla's sensors hand it.
 * Their memories of "the nearest player" are typed {@code Player}, so a Person is never put in
 * them — code that reads them casts — and the Person is offered where they are read instead (the
 * aggro plan, 2026-09-30).
 */
public final class PersonAggro {

    private PersonAggro() {
    }

    /** The nearest Person this mob sees and may attack, and that {@code also} accepts. */
    public static Optional<LivingEntity> nearestPerson(ServerLevel level, Mob body,
                                                       Predicate<LivingEntity> also) {
        return body.getBrain().getMemory(MemoryModuleType.NEAREST_VISIBLE_LIVING_ENTITIES)
                .flatMap(seen -> seen.findClosest(entity -> entity instanceof Person && also.test(entity)
                        && Sensor.isEntityAttackable(level, body, entity)));
    }

    /** Whichever of the player vanilla found and the Person is nearer; either may be absent. */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public static Optional nearer(Mob body, Optional player, Optional<LivingEntity> person) {
        if (person.isEmpty()) {
            return player;
        }
        if (player.isEmpty()) {
            return person;
        }
        LivingEntity them = (LivingEntity) player.get();
        return body.distanceToSqr(person.get()) < body.distanceToSqr(them) ? person : player;
    }
}
