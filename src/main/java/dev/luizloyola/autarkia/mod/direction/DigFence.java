package dev.luizloyola.autarkia.mod.direction;

import dev.luizloyola.anima.core.brain.sense.Pos;
import dev.luizloyola.anima.core.nav.HandsOff;
import dev.luizloyola.autarkia.core.earthwork.DigDirt;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.server.MinecraftServer;
import org.jspecify.annotations.Nullable;

/**
 * Where {@link DigDirt} may not scrape: the settled ground ({@link SettledGround}), which includes
 * every flatten and building site with the ring it eases into, so dirt for a pad never comes out of
 * that pad's own slope.
 */
public final class DigFence {

    private static volatile @Nullable MinecraftServer live;

    private DigFence() {
    }

    public static void init() {
        ServerLifecycleEvents.SERVER_STARTED.register(server -> live = server);
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> live = null);
        DigDirt.fenceBy(DigFence::around);
    }

    static HandsOff around(Pos near, int reach) {
        MinecraftServer server = live;
        if (server == null) {
            return HandsOff.NONE;
        }
        return SettledGround.within(server, near.x() - reach, near.z() - reach, near.x() + reach,
                near.z() + reach);
    }
}
