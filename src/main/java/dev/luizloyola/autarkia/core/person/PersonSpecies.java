package dev.luizloyola.autarkia.core.person;

import dev.luizloyola.anima.core.agent.ProfileAspect;
import dev.luizloyola.anima.core.agent.SpeciesKnobs;
import dev.luizloyola.anima.core.agent.SpeciesProfile;

/**
 * What a Person is like — Autarkia's answer to every aspect of a mind Anima names.
 *
 * <p>These numbers used to be Anima's, in {@code anima.json}, applying to every agent in the world;
 * 24 blocks of eyesight and a 150° cone describe a settler, not a wolf. The library kept the schema
 * ({@link ProfileAspect}) and the mod that ships the body took the values, unchanged to the digit.
 *
 * <p>{@link SpeciesProfile.Builder#build()} hard-fails naming anything left unanswered, so a new
 * Anima aspect stops the build here until somebody decides what a settler thinks about it — better
 * than quietly inheriting a library's guess.
 */
public final class PersonSpecies {

    /** The species key — a config path segment and a readout label. */
    public static final String KEY = "person";

    public static final SpeciesProfile PROFILE = SpeciesProfile.of(KEY)
            // --- mind: dogged enough to finish a tree, not so dogged it ignores a creeper -----
            .set(ProfileAspect.MIND_STICKINESS, 0.1)
            .set(ProfileAspect.MIND_PREEMPT, 0.6)
            // --- instincts -------------------------------------------------------------------
            .set(ProfileAspect.FLEE_RANGE, 16.0)
            .set(ProfileAspect.FLEE_RAMP, 12.0)
            .set(ProfileAspect.FLEE_APPROACH_BONUS, 1.3)
            .set(ProfileAspect.WANDER_IDLE_PRESSURE, 0.15)
            .set(ProfileAspect.WANDER_RADIUS, 8)
            // --- senses: human-shaped vision, wide across and flat up-down --------------------
            .set(ProfileAspect.SENSES_RADIUS, 24)
            .set(ProfileAspect.SENSES_SNEAK_RANGE_MULT, 0.75)
            .set(ProfileAspect.SENSES_CONE_DEGREES, 150)
            .set(ProfileAspect.SENSES_VERTICAL_DEGREES, 60)
            .set(ProfileAspect.SENSES_HEARING_RADIUS, 12)
            .set(ProfileAspect.SENSES_LINGER_TICKS, 300)
            .set(ProfileAspect.SENSES_HEARD_DECAY_TICKS, 60)
            .set(ProfileAspect.SENSES_NEAR_INTERVAL, 1)
            .set(ProfileAspect.SENSES_FAR_INTERVAL, 20)
            // 30 seconds: long enough to keep running from what shot you, short enough that a
            // settler is not permanently haunted by one bad afternoon.
            .set(ProfileAspect.SENSES_ATTACK_DECAY_TICKS, 600)
            .set(ProfileAspect.SENSES_HERD_LINK_RADIUS, 12)
            // --- places: the same reach and aperture as the eyes above, because they ARE the
            //     eyes — a settler who spots a cow at 24 blocks and misses the tree beside it
            //     reads as broken, and did. The 8-block halo is the boot and the periphery: what
            //     you are standing next to is noticed whichever way you face -------------------
            .set(ProfileAspect.PLACES_RADIUS, 24)
            .set(ProfileAspect.PLACES_CONE_DEGREES, 150)
            .set(ProfileAspect.PLACES_NEAR_RADIUS, 8)
            // Well past anything they could inspect: a forest is obvious from open ground long
            // before any one tree in it is. ~15 marched rays per bearing makes a full sweep of the
            // cone about 10,000 reads — 160 ticks at the wallet. That is what sets REFRESH_TICKS.
            // The reach buys freshness, not per-tick cost.
            .set(ProfileAspect.PLACES_HORIZON_RADIUS, 64)
            // The same 8 blocks the halo reaches: about as far as a settler resolves a thing into
            // parts rather than outline. Past it a wood is a green wall, so standing in
            // one leaves nearly no skyline. Its own number, so better eyes see into more without
            // noticing more behind.
            .set(ProfileAspect.PLACES_SEE_THROUGH_RADIUS, 8)
            .set(ProfileAspect.PLACES_REGION_MAX_SPREAD, 24)
            .set(ProfileAspect.PLACES_MAX_PER_KIND, 160)
            // --- danger: armored < with sword < with bow (decision: Luiz) ---------------------
            .set(ProfileAspect.DANGER_MELEE_MULT, 1.15)
            .set(ProfileAspect.DANGER_RANGED_MULT, 1.25)
            .set(ProfileAspect.DANGER_ARMORED_MULT, 1.2)
            .set(ProfileAspect.DANGER_MOUNTED_MULT, 1.15)
            .set(ProfileAspect.DANGER_BABY_MULT, 1.2)
            // --- social: must outrange sight, or a shout adds nothing to looking -------
            .set(ProfileAspect.SOCIAL_HAIL_RADIUS, 48)
            // Company, as a band a settler tries to sit in: [0.35, 0.85] — wide, so most of a
            // working day passes with nothing to say about it.
            .set(ProfileAspect.SOCIAL_COMPANY_CENTER, 0.6)
            .set(ProfileAspect.SOCIAL_COMPANY_WIDTH, 0.5)
            // The two rates matter only against each other: two in-game days of solitude empties a
            // settler, one neighbour fills at twice that — so nothing has to define "a crowd".
            .set(ProfileAspect.SOCIAL_COMPANY_SOLITUDE_TICKS, 48_000)
            .set(ProfileAspect.SOCIAL_COMPANY_PROXIMITY_TICKS, 24_000)
            // Conversation is counted in LINES, not time — see the aspect. Thirty fills a settler
            // from empty, so the social spec's worked transcript (about fifteen lines) is half a
            // bar. Starting values, to be tuned in world.
            .set(ProfileAspect.SOCIAL_COMPANY_UTTERANCES, 30)
            // --- body: a 1.8 hitbox, a vanilla jump, and vanilla's sprint-jump limit ----------
            .set(ProfileAspect.BODY_HEIGHT, 2)
            .set(ProfileAspect.BODY_JUMP_HEIGHT, 1)
            .set(ProfileAspect.BODY_MAX_DROP, 3)
            .set(ProfileAspect.BODY_MAX_LEAP, 3)
            .set(ProfileAspect.BODY_CAN_SWIM, true)
            .build();

    /** The same declaration as tunables in {@code autarkia.json}, under {@code person.*}. */
    public static final SpeciesKnobs KNOBS = SpeciesKnobs.of(PROFILE);

    private PersonSpecies() {
    }
}
