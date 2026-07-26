package dev.luizloyola.autarkia.mod.brain;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;

/**
 * Who placed blocks lately — {@link DamageMarks}' sibling for the builder's arm: a landing block is
 * visible and audible, so a short-lived mark on the PLACER is fair perception knowledge.
 * {@code PeerSense} reads it to tell BUILDING from MINING, whose animation is identical. Fed by the
 * game-event choke point, Persons included. Server-wide and transient.
 */
public final class PlaceMarks {
    private PlaceMarks() {}

    /** Placer uuid → game time of their last landed block. Pruned opportunistically. */
    private static final Map<UUID, Long> PLACED_AT = new HashMap<>();
    private static final int PRUNE_ABOVE = 256;
    private static final int PRUNE_OLDER_THAN = 1200;

    /** Call once from mod init: clears on server stop (the marks are fed by the mixin). */
    public static void init() {
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> PLACED_AT.clear());
    }

    public static void mark(UUID who, long now) {
        PLACED_AT.put(who, now);
        if (PLACED_AT.size() > PRUNE_ABOVE) {
            PLACED_AT.values().removeIf(at -> now - at > PRUNE_OLDER_THAN);
        }
    }

    public static boolean placedWithin(UUID who, long now, int ticks) {
        Long at = PLACED_AT.get(who);
        return at != null && now - at <= ticks;
    }
}
