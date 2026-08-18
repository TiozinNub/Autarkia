package dev.luizloyola.autarkia.core.person;

import dev.luizloyola.anima.core.agent.Pronouns;
import java.util.random.RandomGenerator;

/**
 * A person's gender — part of their external, visible {@link Appearance}.
 *
 * <p>Binary for now: it seeds appearance (skin/model pools) and the future family/reproduction
 * mechanics. Not a statement about gender identity in general; it can grow.
 *
 * <p>It supplies Anima's {@link Pronouns}, which asks only for three words so a wolf or a construct
 * can answer differently. A third value adds its forms here and every journal line says the right
 * word untouched.
 */
public enum Gender implements Pronouns {
    MALE,
    FEMALE;

    public static Gender random(RandomGenerator random) {
        return random.nextBoolean() ? MALE : FEMALE;
    }

    public <T> T choose(T ifMale, T ifFemale) {
        return this == MALE ? ifMale : ifFemale;
    }

    /** What to call this to a reader: {@code autarkia.gender.male} → "male". */
    public String nameKey() {
        return "autarkia.gender." + name().toLowerCase(java.util.Locale.ROOT);
    }

    /**
     * The subject pronoun for narration ("the someone he/she had heard"). Gains a "they" if the
     * enum grows: NOTHING that narrates a person may spell a pronoun itself, or the day a third
     * value lands it narrates a lie.
     */
    @Override
    public String subject() {
        return choose("he", "she");
    }

    /** The object pronoun for narration ("watching him/her"). Gains a "them" if the enum grows. */
    @Override
    public String object() {
        return choose("him", "her");
    }

    /** The possessive for narration ("his/her beliefs"). Gains a "their" if the enum grows. */
    @Override
    public String possessive() {
        return choose("his", "her");
    }
}
