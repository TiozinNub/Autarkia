package dev.luizloyola.autarkia.core.person;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The genotype's trip through the one string that is its disk form, its wire form and its identity.
 *
 * <p>A mistake in it loses a person's face across a restart (the store writes it), makes a client
 * draw somebody else (the entity syncs it), or re-bakes a texture sixty times a second (it is
 * compared to decide whether a look moved).
 */
class ComposedLookTest {

    private static Look.Composed genotype() {
        Map<String, Integer> ladders = new LinkedHashMap<>();
        ladders.put("skin", 3);
        ladders.put("hair", 5);
        ladders.put("cloth_a", 2);
        Map<String, String> choices = new LinkedHashMap<>();
        choices.put("hairstyle", "long");
        choices.put("shirt", "plain");
        return new Look.Composed(ladders, choices);
    }

    @Test
    void aGenotypeSurvivesTheRoundTrip() {
        Appearance before = new Appearance(Gender.FEMALE, ModelType.SLIM, genotype());
        Appearance after = Appearance.decode(before.encode());
        assertEquals(before, after);
        assertEquals(genotype(), after.look());
    }

    /**
     * Two agents built the same way must encode identically <b>byte for byte</b>, whatever their map
     * order: the encoded look is hashed into the texture id, so an unstable spelling mints two
     * textures for one appearance — a cache that works but silently stops sharing.
     */
    @Test
    void theSpellingDoesNotDependOnMapOrder() {
        Map<String, Integer> reversed = new LinkedHashMap<>();
        reversed.put("cloth_a", 2);
        reversed.put("hair", 5);
        reversed.put("skin", 3);
        Map<String, String> alsoReversed = new LinkedHashMap<>();
        alsoReversed.put("shirt", "plain");
        alsoReversed.put("hairstyle", "long");
        assertEquals(genotype().encode(), new Look.Composed(reversed, alsoReversed).encode());
    }

    /** An operator pinning a texture onto somebody is the more specific statement, so it wins. */
    @Test
    void aPinnedSkinBeatsAComposedLookOnTheSameRow() {
        String both = "gender=MALE;model=WIDE;skin=minecraft:entity/player/wide/steve;"
                + "l.skin=3;p.hairstyle=long";
        assertInstanceOf(Look.Skin.class, Appearance.decode(both).look());
    }

    /** The older form keeps reading exactly as it always did — no migration, no rewrite. */
    @Test
    void aRowWrittenBeforeGenotypesExistedStillReads() {
        Appearance old = Appearance.decode("gender=MALE;model=WIDE;skin=minecraft:entity/player/wide/kai");
        assertEquals(new Look.Skin("minecraft:entity/player/wide/kai"), old.look());
    }

    /** A row with nothing on it is the default, not an empty genotype that would draw nothing. */
    @Test
    void anEmptyRowIsTheDefaultLook() {
        assertSame(Look.DEFAULT, Appearance.decode("gender=MALE;model=WIDE").look());
    }

    /**
     * One unreadable field costs that field and nothing else. A ladder index is wrapped on use, so
     * any number at all draws a colour — which is the whole reason the fallback can be this blunt.
     */
    @Test
    void aCorruptLadderIndexCostsOneFieldRatherThanThePerson() {
        Look look = Appearance.decode("gender=MALE;model=WIDE;l.skin=banana;p.hairstyle=short").look();
        Look.Composed composed = assertInstanceOf(Look.Composed.class, look);
        assertEquals(0, composed.ladders().get("skin"));
        assertEquals("short", composed.choices().get("hairstyle"));
    }

    /** A field naming something the catalog no longer has is carried, not rejected — the composer
     *  finds no slot reading it, and one dropped hairstyle must not cost a body. */
    @Test
    void anUnknownParameterIsCarriedRatherThanRefused() {
        Look look = Appearance.decode("gender=MALE;model=WIDE;p.tattoo=anchor").look();
        assertEquals("anchor", assertInstanceOf(Look.Composed.class, look).choices().get("tattoo"));
    }
}
