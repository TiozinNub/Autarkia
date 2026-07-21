package dev.luizloyola.autarkia.core.person;

import java.util.random.RandomGenerator;

/**
 * A person's gender — part of their external, visible {@link Appearance}.
 *
 * <p>Binary for now: it seeds appearance (skin/model pools) and the future family/reproduction
 * mechanics. Not a statement about gender identity in general — it can grow (more values, or a
 * separate identity axis) if the simulation needs it.
 */
public enum Gender {
    MALE,
    FEMALE;

    public static Gender random(RandomGenerator random) {
        return random.nextBoolean() ? MALE : FEMALE;
    }

    public <T> T choose(T ifMale, T ifFemale) {
        return this == MALE ? ifMale : ifFemale;
    }
}
