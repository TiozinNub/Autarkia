package dev.luizloyola.autarkia.mod.brain;

import dev.luizloyola.autarkia.core.brain.sense.Being;
import dev.luizloyola.autarkia.core.config.Config;
import dev.luizloyola.autarkia.core.config.Knob;
import dev.luizloyola.autarkia.mod.entity.Person;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySelector;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * The knock: mining-in-progress delivered to nearby ears (see {@code ServerLevelCrackMixin} for why
 * the crack broadcast is the sound). Knocks are LOUD — they carry regardless of the view cone,
 * walls, or a CROUCHING miner. Once per crack stage, so a bare-hand break knocks ~10 times, inside
 * the ear's freshness window. Person-only: mobs do not mine (door-banging rides ENTITY_DAMAGE).
 */
public final class BeingKnocks {
    private BeingKnocks() {}

    public static void onCrack(ServerLevel level, int breakerId, BlockPos pos) {
        Entity breaker = level.getEntity(breakerId);
        if (!(breaker instanceof LivingEntity body) || !body.isAlive()) {
            return;
        }
        boolean personShaped = body instanceof Person
                || (body instanceof Player player && !player.isSpectator());
        if (!personShaped) {
            return;
        }
        int radius = Config.get().i(Knob.PEERS_HEARING_RADIUS);
        if (radius <= 0) {
            return;
        }
        Vec3 at = Vec3.atCenterOf(pos);
        // Ears belong to the living: heard() writes into the sense table of a killed Person, whose
        // corpse lingers, and nothing will ever read them — the commands and every @e selector
        // treat it as gone. Same guard the aggro mixin applies.
        for (Person listener : level.getEntitiesOfClass(Person.class,
                AABB.ofSize(at, radius * 2.0, radius * 2.0, radius * 2.0),
                EntitySelector.LIVING_ENTITY_STILL_ALIVE)) {
            if (listener != body && at.distanceTo(listener.getEyePosition()) <= radius) {
                listener.beingSense().heard(body, Being.Activity.MINING,
                        Being.Locomotion.STILL, false);
            }
        }
    }
}
