package dev.luizloyola.autarkia.mod.brain;

import dev.luizloyola.autarkia.core.brain.sense.Peer;
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
 * Mining-in-progress pushed to nearby Persons' ears (see {@code ServerLevelCrackMixin} for why
 * the crack broadcast is the sound). Knocks are LOUD: they carry through the view cone, walls and
 * the miner CROUCHING. Once per crack stage — a bare-hand break knocks ~10 times.
 */
public final class PeerKnocks {
    private PeerKnocks() {}

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
        // Ears belong to the living: a killed Person lingers as a corpse and heard() writes
        // synchronously, so without this filter it accrues peers nothing ever reads. Same guard
        // the aggro mixin applies to its Person scan.
        for (Person listener : level.getEntitiesOfClass(Person.class,
                AABB.ofSize(at, radius * 2.0, radius * 2.0, radius * 2.0),
                EntitySelector.LIVING_ENTITY_STILL_ALIVE)) {
            if (listener != body && at.distanceTo(listener.getEyePosition()) <= radius) {
                listener.peerSense().heard(body, Peer.Activity.MINING, Peer.Locomotion.STILL);
            }
        }
    }
}
