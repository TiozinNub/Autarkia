package dev.luizloyola.autarkia.mod.brain;

import dev.luizloyola.autarkia.core.brain.sense.Being;
import dev.luizloyola.autarkia.core.config.Config;
import dev.luizloyola.autarkia.core.config.Knob;
import dev.luizloyola.autarkia.mod.entity.Person;
import net.minecraft.core.Holder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.gameevent.EntityPositionSource;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.world.level.gameevent.GameEventListener;
import net.minecraft.world.level.gameevent.PositionSource;
import net.minecraft.world.phys.Vec3;

/**
 * A Person's EAR — a {@link GameEventListener} on the sculk vibration machinery, vanilla's own
 * model of things that make noise. Push-based: hearing costs nothing until something sounds. It
 * hears every living body and applies the universal ladder (decision: Luiz — "applies to all things
 * really"):
 *
 * <ul>
 *   <li>a step or any incidental noise says POSITION only;</li>
 *   <li>a VOICE — {@code autarkia:being_voice} from the voice mixins, or vanilla's
 *       {@code PROJECTILE_SHOOT} (a bowshot sounds like its maker — decision: Luiz) — names the
 *       SPECIES, never the individual;</li>
 *   <li>only sight, elsewhere, tells the rest.</li>
 * </ul>
 *
 * <p>A person's sounds carry a per-event story; a creature's incidental sounds carry no occupation.
 * Sneaking quiets FEET for anyone; LOUD events — work, containers, violence, projectiles, voices —
 * carry regardless.
 */
public final class BeingEar implements GameEventListener {
    private final Person person;
    private final PositionSource source;

    public BeingEar(Person person) {
        this.person = person;
        this.source = new EntityPositionSource(person, person.getEyeHeight());
    }

    @Override
    public PositionSource getListenerSource() {
        return source;
    }

    /**
     * The LOUDEST social range, not the hearing range: the registry culls by this number
     * (verified in 26.1.2 bytecode — {@code EuclideanGameEventListenerRegistry#
     * getPostableListenerPosition} squares it against the event position), so an ear sized to
     * footsteps never receives a hail. Quieter sounds are narrowed per event in
     * {@link #handleGameEvent} (decision: Luiz).
     *
     * <p>Only half the gate: {@code GameEventDispatcher#post} visits chunk sections by the EVENT's
     * own {@code notificationRadius}, so a long-range sound must also be registered that wide (see
     * {@link BeingVoices}).
     */
    @Override
    public int getListenerRadius() {
        return Math.max(Config.get().i(Knob.PEERS_HEARING_RADIUS),
                Config.get().i(Knob.SOCIAL_HAIL_RADIUS));
    }

    @Override
    public boolean handleGameEvent(ServerLevel level, Holder<GameEvent> event,
                                   GameEvent.Context context, Vec3 pos) {
        if (!(context.sourceEntity() instanceof LivingEntity body)
                || body == person || !body.isAlive() || body instanceof ArmorStand
                || (body instanceof Player player && player.isSpectator())) {
            return false;
        }
        // The ear is sized to the loudest social range, so every ordinary sound is narrowed back
        // to the hearing knob here; only a deliberate hail opts out, once the speech slice
        // registers it (whisper 4, chat 12, hail 48). Load-bearing regardless: the vibration
        // dispatch's broadphase is chunk-section coarse — a sheep was heard from 43 blocks.
        if (pos.distanceTo(person.getEyePosition()) > Config.get().i(Knob.PEERS_HEARING_RADIUS)) {
            return false;
        }
        if (!loud(event) && body.isCrouching()) {
            return false; // sneaking quiets FEET (decision: Luiz) — not a pick against stone
        }
        boolean voice = event.is(BeingVoices.KEY) || event.is(GameEvent.PROJECTILE_SHOOT);
        boolean personShaped = body instanceof Person
                || (body instanceof Player);
        Being.Activity heardAs = personShaped ? activityOf(event) : Being.Activity.IDLE;
        Being.Locomotion heardMoving = personShaped ? locomotionOf(event) : Being.Locomotion.STILL;
        person.beingSense().heard(body, heardAs, heardMoving, voice);
        return true;
    }

    /** Sounds that carry regardless of crouching. */
    private static boolean loud(Holder<GameEvent> event) {
        return event.is(GameEvent.BLOCK_DESTROY) || event.is(GameEvent.BLOCK_PLACE)
                || event.is(GameEvent.CONTAINER_OPEN) || event.is(GameEvent.CONTAINER_CLOSE)
                || event.is(GameEvent.ENTITY_DAMAGE)
                || event.is(GameEvent.PROJECTILE_SHOOT) || event.is(GameEvent.PROJECTILE_LAND)
                || event.is(BeingVoices.KEY);
    }

    /**
     * What the SOUND says a PERSON is doing — ears don't run the visual classifier (a heard-only
     * peer once read "at_crafting" through the back of their head). The sensor keeps this reading
     * until the ear or the eyes say otherwise.
     */
    private static Being.Activity activityOf(Holder<GameEvent> event) {
        if (event.is(GameEvent.BLOCK_DESTROY)) {
            return Being.Activity.MINING;
        }
        if (event.is(GameEvent.BLOCK_PLACE)) {
            return Being.Activity.BUILDING; // a landing block sounds different from a breaking one
        }
        if (event.is(GameEvent.CONTAINER_OPEN)) {
            return Being.Activity.AT_CHEST;
        }
        if (event.is(GameEvent.CONTAINER_CLOSE)) {
            // The closing lid says DONE there — mapping it to AT_CHEST re-stamped the
            // activity on the way out and it never cleared (caught live).
            return Being.Activity.IDLE;
        }
        if (event.is(GameEvent.PROJECTILE_SHOOT)) {
            return Being.Activity.AIMING;
        }
        if (event.is(GameEvent.ENTITY_DAMAGE)) {
            return Being.Activity.FIGHTING;
        }
        if (event.is(GameEvent.EAT)) {
            return Being.Activity.EATING;
        }
        return Being.Activity.IDLE; // no occupation story in this sound — the legs may differ
    }

    /** What the sound says about the LEGS. */
    private static Being.Locomotion locomotionOf(Holder<GameEvent> event) {
        boolean feet = event.is(GameEvent.STEP) || event.is(GameEvent.SWIM)
                || event.is(GameEvent.HIT_GROUND);
        return feet ? Being.Locomotion.WALKING : Being.Locomotion.STILL;
    }
}
