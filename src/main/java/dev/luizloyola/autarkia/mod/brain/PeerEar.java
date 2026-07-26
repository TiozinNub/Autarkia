package dev.luizloyola.autarkia.mod.brain;

import dev.luizloyola.autarkia.core.config.Config;
import dev.luizloyola.autarkia.core.config.Knob;
import dev.luizloyola.autarkia.mod.entity.Person;
import net.minecraft.core.Holder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.gameevent.EntityPositionSource;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.world.level.gameevent.GameEventListener;
import net.minecraft.world.level.gameevent.PositionSource;
import net.minecraft.world.phys.Vec3;

/**
 * A Person's EAR — a {@link GameEventListener} on the sculk vibration machinery, vanilla's model
 * of "things that make noise": steps, hits, containers, arrows, blocks breaking. Push-based, so
 * hearing costs nothing until something sounds.
 *
 * <p>Everything a person-shaped body does is hearable (decision: Luiz) except sneaking, covered
 * twice over: vanilla omits STEP for careful feet, and the crouch check below covers the rest.
 */
public final class PeerEar implements GameEventListener {
    private final Person person;
    private final PositionSource source;

    public PeerEar(Person person) {
        this.person = person;
        this.source = new EntityPositionSource(person, person.getEyeHeight());
    }

    @Override
    public PositionSource getListenerSource() {
        return source;
    }

    @Override
    public int getListenerRadius() {
        return Config.get().i(Knob.PEERS_HEARING_RADIUS);
    }

    @Override
    public boolean handleGameEvent(ServerLevel level, Holder<GameEvent> event,
                                   GameEvent.Context context, Vec3 pos) {
        if (!(context.sourceEntity() instanceof LivingEntity body)
                || body == person || !body.isAlive()) {
            return false;
        }
        boolean personShaped = body instanceof Person
                || (body instanceof Player player && !player.isSpectator());
        if (!personShaped || body.isCrouching()) {
            return false; 
        }
        person.peerSense().heard(body);
        return true;
    }
}
