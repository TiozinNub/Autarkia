package dev.luizloyola.autarkia.mod.brain;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.world.entity.LivingEntity;

/**
 * Who dealt damage lately — the observable half of "attacking": a landed hit is visible and
 * audible, so a short-lived mark on the ATTACKER is fair perception knowledge, not telepathy.
 * {@code BeingSense} reads it to split a swinging arm into FIGHTING vs MINING (decision: Luiz).
 * Server-wide and transient, the {@code Claims} shape.
 */
public final class DamageMarks {
    private DamageMarks() {}

    /** Attacker uuid → game time of their last landed hit. Pruned opportunistically. */
    private static final Map<UUID, Long> DEALT_AT = new HashMap<>();
    private static final int PRUNE_ABOVE = 256;
    private static final int PRUNE_OLDER_THAN = 1200;

    /** Call once from mod init: marks attackers on every landed hit, clears on server stop. */
    public static void init() {
        ServerLivingEntityEvents.AFTER_DAMAGE.register((entity, source, baseDamage, damageTaken, blocked) -> {
            if (source.getEntity() instanceof LivingEntity attacker) {
                long now = entity.level().getGameTime();
                DEALT_AT.put(attacker.getUUID(), now);
                if (DEALT_AT.size() > PRUNE_ABOVE) {
                    DEALT_AT.values().removeIf(at -> now - at > PRUNE_OLDER_THAN);
                }
            }
        });
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> DEALT_AT.clear());
    }

    public static boolean dealtWithin(UUID who, long now, int ticks) {
        Long at = DEALT_AT.get(who);
        return at != null && now - at <= ticks;
    }
}
