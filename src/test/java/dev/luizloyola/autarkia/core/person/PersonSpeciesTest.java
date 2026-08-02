package dev.luizloyola.autarkia.core.person;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.luizloyola.anima.core.agent.AgentProfile;
import dev.luizloyola.anima.core.agent.ProfileAspect;
import dev.luizloyola.anima.core.config.KnobSpec;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * What a settler is, pinned.
 *
 * <p>These numbers were Anima's knob defaults, guarded by Anima's suite; they are Autarkia's now —
 * the library has no opinion about how far a Person sees — so the guard moved with them. Every
 * value below is unchanged from what {@code anima.json} shipped before the split, the claim this
 * file enforces.
 */
class PersonSpeciesTest {

    private static final AgentProfile PERSON = PersonSpecies.PROFILE.fixed();

    @Test
    @DisplayName("a settler still has the numbers Anima used to hand every agent in the world")
    void theDeclarationMatchesTheOldGlobalDefaults() {
        assertEquals(0.1, PERSON.d(ProfileAspect.MIND_STICKINESS), "brain.stickiness");
        assertEquals(0.6, PERSON.d(ProfileAspect.MIND_PREEMPT), "brain.preempt");

        assertEquals(16.0, PERSON.d(ProfileAspect.FLEE_RANGE), "instincts.flee_range");
        assertEquals(12.0, PERSON.d(ProfileAspect.FLEE_RAMP), "instincts.flee_ramp");
        assertEquals(1.3, PERSON.d(ProfileAspect.FLEE_APPROACH_BONUS), "flee_approach_bonus");
        assertEquals(0.15, PERSON.d(ProfileAspect.WANDER_IDLE_PRESSURE), "wander_idle_pressure");
        assertEquals(8, PERSON.i(ProfileAspect.WANDER_RADIUS), "wander_radius");

        assertEquals(24, PERSON.i(ProfileAspect.SENSES_RADIUS), "peers.radius");
        assertEquals(0.75, PERSON.d(ProfileAspect.SENSES_SNEAK_RANGE_MULT), "sneak_range_mult");
        assertEquals(150, PERSON.i(ProfileAspect.SENSES_CONE_DEGREES), "cone_degrees");
        assertEquals(60, PERSON.i(ProfileAspect.SENSES_VERTICAL_DEGREES), "vertical_degrees");
        assertEquals(12, PERSON.i(ProfileAspect.SENSES_HEARING_RADIUS), "hearing_radius");
        assertEquals(300, PERSON.i(ProfileAspect.SENSES_LINGER_TICKS), "linger_ticks");
        assertEquals(60, PERSON.i(ProfileAspect.SENSES_HEARD_DECAY_TICKS), "heard_decay_ticks");
        assertEquals(1, PERSON.i(ProfileAspect.SENSES_NEAR_INTERVAL), "near_interval_ticks");
        assertEquals(20, PERSON.i(ProfileAspect.SENSES_FAR_INTERVAL), "far_interval_ticks");
        assertEquals(12, PERSON.i(ProfileAspect.SENSES_HERD_LINK_RADIUS), "herd_link_radius");

        assertEquals(12, PERSON.i(ProfileAspect.PLACES_RADIUS), "perception.sense_radius");
        assertEquals(24, PERSON.i(ProfileAspect.PLACES_REGION_MAX_SPREAD), "region_max_spread");
        assertEquals(160, PERSON.i(ProfileAspect.PLACES_MAX_PER_KIND), "knowledge_max_per_kind");

        assertEquals(1.15, PERSON.d(ProfileAspect.DANGER_MELEE_MULT), "danger.melee_mult");
        assertEquals(1.25, PERSON.d(ProfileAspect.DANGER_RANGED_MULT), "danger.ranged_mult");
        assertEquals(1.2, PERSON.d(ProfileAspect.DANGER_ARMORED_MULT), "danger.armored_mult");
        assertEquals(1.15, PERSON.d(ProfileAspect.DANGER_MOUNTED_MULT), "danger.mounted_mult");
        assertEquals(1.2, PERSON.d(ProfileAspect.DANGER_BABY_MULT), "danger.baby_mult");

        assertEquals(48, PERSON.i(ProfileAspect.SOCIAL_HAIL_RADIUS), "social.hail_radius");

        // The movement capabilities were never knobs — they were the AgentProfile.PERSON record.
        assertEquals(2, PERSON.i(ProfileAspect.BODY_HEIGHT), "a 1.8 hitbox is 2 cells");
        assertEquals(1, PERSON.i(ProfileAspect.BODY_JUMP_HEIGHT), "vanilla jump");
        assertEquals(3, PERSON.i(ProfileAspect.BODY_MAX_DROP), "no fall damage worth fearing");
        assertEquals(3, PERSON.i(ProfileAspect.BODY_MAX_LEAP), "vanilla sprint-jump limit");
        assertTrue(PERSON.b(ProfileAspect.BODY_CAN_SWIM), "settlers swim");
    }

    @Test
    @DisplayName("the invariants a settler's own docs depend on")
    void theInvariantsBetweenAspectsHold() {
        assertTrue(PERSON.i(ProfileAspect.SOCIAL_HAIL_RADIUS) > PERSON.i(ProfileAspect.SENSES_RADIUS),
                "a hail that does not outrange sight adds nothing to simply noticing someone");
        assertTrue(PERSON.i(ProfileAspect.SENSES_HEARING_RADIUS) < PERSON.i(ProfileAspect.SENSES_RADIUS),
                "hearing is the shorter channel inside the notice radius");
    }

    @Test
    @DisplayName("the generated knobs land in autarkia.json, namespaced under the species")
    void theKnobsAreOursAndNamespaced() {
        for (ProfileAspect aspect : ProfileAspect.values()) {
            KnobSpec knob = PersonSpecies.KNOBS.knob(aspect);
            assertEquals("person.anima_settings." + aspect.key(), knob.key());
            assertEquals(PersonSpecies.PROFILE.get(aspect), knob.def(),
                    knob.key() + " must default to what the species declared");
        }
        assertEquals(ProfileAspect.values().length, PersonSpecies.KNOBS.knobs().size(),
                "the whole schema is generated, never a hand-picked subset");
    }
}
