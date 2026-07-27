package dev.luizloyola.autarkia.mod.brain;

import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.gameevent.GameEvent;

/**
 * The voice channel — {@code autarkia:being_voice}, a REGISTERED game event emitted whenever a
 * living body sounds like itself: an idle call ({@code Mob#playAmbientSound}) or a hurt cry
 * ({@code LivingEntity#playHurtSound}), hooked by the two voice mixins. Vanilla plays those as
 * plain sounds — no game event, so a sculk sensor ignores a mooing cow and why the
 * ear was structurally deaf to them (verified in 26.1.2 bytecode). Riding a custom event
 * through the vibration bus is the "elegant" dispatch (decision: Luiz): vanilla's own listener
 * registry does the spatial delivery and {@link BeingEar} receives it like any other sound.
 *
 * <p>A voice NAMES its SPECIES — the identification ladder's middle rung: a groan behind a
 * wall makes "something" into "a zombie", sight never involved. (A bowshot does the same via
 * vanilla's own {@code PROJECTILE_SHOOT}; the ear treats both as voice-tier.)
 *
 * <p>Vanilla listeners must stay deaf to it: sculk-family listeners map events to vibration
 * frequencies and an unregistered event's frequency is 0 → filtered before any state changes
 * (checked at build; regression-checked live with a sculk sensor).
 */
public final class BeingVoices {
    public static final Identifier ID = Identifier.fromNamespaceAndPath("autarkia", "being_voice");
    public static final ResourceKey<GameEvent> KEY = ResourceKey.create(Registries.GAME_EVENT, ID);

    /** The registered event; set once in {@link #init}. Radius covers the hearing knob's max. */
    private static Holder<GameEvent> voice;

    private BeingVoices() {
    }

    /** Call once from mod init, before any level exists. */
    public static void init() {
        voice = Registry.registerForHolder(BuiltInRegistries.GAME_EVENT, KEY, new GameEvent(32));
    }

    /**
     * A body just voiced — put it on the bus. Called by the voice mixins at the exact points
     * vanilla decides a real sound plays (never for silent bodies or soundless species, which
     * the mixins already filtered: a voice that made no sound identifies nothing).
     */
    public static void voiced(LivingEntity body) {
        if (voice != null && !body.level().isClientSide()) {
            body.level().gameEvent(voice, body.position(), GameEvent.Context.of(body));
        }
    }
}
