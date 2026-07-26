package dev.luizloyola.autarkia.mod.brain;

import dev.luizloyola.autarkia.core.brain.sense.Peer;
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
        if (!personShaped) {
            return false;
        }
        if (!loud(event) && body.isCrouching()) {
            return false; // sneaking quiets FEET (decision: Luiz) — not a pick against stone
        }
        person.peerSense().heard(body, activityOf(event), locomotionOf(event));
        return true;
    }

    /** Sounds that carry regardless of crouching: work, containers, violence, projectiles. */
    private static boolean loud(Holder<GameEvent> event) {
        return event.is(GameEvent.BLOCK_DESTROY) || event.is(GameEvent.BLOCK_PLACE)
                || event.is(GameEvent.CONTAINER_OPEN) || event.is(GameEvent.CONTAINER_CLOSE)
                || event.is(GameEvent.ENTITY_DAMAGE)
                || event.is(GameEvent.PROJECTILE_SHOOT) || event.is(GameEvent.PROJECTILE_LAND);
    }

    /**
     * What the SOUND says they're doing — ears don't run the visual classifier (a heard-only peer
     * read "at_crafting" through the back of her head). Coarse, and kept until the ear or the eyes
     * say otherwise.
     */
    private static Peer.Activity activityOf(Holder<GameEvent> event) {
        if (event.is(GameEvent.BLOCK_DESTROY)) {
            return Peer.Activity.MINING;
        }
        if (event.is(GameEvent.BLOCK_PLACE)) {
            return Peer.Activity.BUILDING; // a landing block sounds different from a breaking one
        }
        if (event.is(GameEvent.CONTAINER_OPEN)) {
            return Peer.Activity.AT_CHEST;
        }
        if (event.is(GameEvent.CONTAINER_CLOSE)) {
            // The closing lid says DONE there — mapping it to AT_CHEST re-stamped the
            // activity on the way out and it never cleared (caught live).
            return Peer.Activity.IDLE;
        }
        if (event.is(GameEvent.PROJECTILE_SHOOT)) {
            return Peer.Activity.AIMING;
        }
        if (event.is(GameEvent.ENTITY_DAMAGE)) {
            return Peer.Activity.FIGHTING;
        }
        if (event.is(GameEvent.EAT)) {
            return Peer.Activity.EATING;
        }
        return Peer.Activity.IDLE; // no occupation story in this sound — the legs may differ
    }

    /** What the sound says about the LEGS. */
    private static Peer.Locomotion locomotionOf(Holder<GameEvent> event) {
        boolean feet = event.is(GameEvent.STEP) || event.is(GameEvent.SWIM)
                || event.is(GameEvent.HIT_GROUND);
        return feet ? Peer.Locomotion.WALKING : Peer.Locomotion.STILL;
    }
}
