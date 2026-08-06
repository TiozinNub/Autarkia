package dev.luizloyola.autarkia.core.person;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.luizloyola.anima.core.appearance.catalog.Catalog;
import dev.luizloyola.anima.core.appearance.catalog.CatalogReader;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** What a rolled face owes: it is theirs, it is stable, and it is drawn from art that exists. */
class GenotypesTest {

    private static Catalog catalog() {
        return CatalogReader.read("""
                {
                  "canvas": [64, 64],
                  "anchors": { "SHEET": [0, 0, 64, 64], "FACE": [8, 8, 8, 8] },
                  "ladders": { "skin": ["F5D0B0", "CE8E68", "8E573A", "3A2015"],
                               "hair": ["0F0D0C", "6B4423", "E8CE8F"] },
                  "slots": [
                    { "name": "shirt", "anchor": "SHEET",
                      "select": [ { "texture": "a:person/shirts/{shirt}" } ] },
                    { "name": "uniform", "anchor": "SHEET", "optional": true,
                      "select": [ { "texture": "a:person/uniforms/{job}" } ] },
                    { "name": "beard", "anchor": "FACE", "optional": true,
                      "select": [ { "texture": "a:person/beard/{beard}" } ] },
                    { "name": "eyes", "anchor": "FACE", "dynamic": true,
                      "select": [ { "texture": "a:person/eyes/{mood}" } ] }
                  ]
                }
                """);
    }

    private static final Map<String, List<String>> CHOICES = Map.of(
            "shirt", List.of("plain", "blouse"),
            "beard", List.of("plain"),
            "job", List.of("farmer"),
            "mood", List.of("neutral", "happy"));

    /** The same person is the same person — the whole reason the seed comes from their id. */
    @Test
    void oneSeedAlwaysRollsTheSameFace() {
        assertEquals(Genotypes.roll(4242L, catalog(), CHOICES),
                Genotypes.roll(4242L, catalog(), CHOICES));
    }

    /** And two people are not the same person. */
    @Test
    void differentSeedsDiverge() {
        assertNotEquals(Genotypes.roll(1L, catalog(), CHOICES).encode(),
                Genotypes.roll(999L, catalog(), CHOICES).encode());
    }

    @Test
    void everyLadderIsPlacedWithinItself() {
        Look.Composed rolled = Genotypes.roll(7L, catalog(), CHOICES);
        assertEquals(Map.of("skin", 4, "hair", 3).keySet(), rolled.ladders().keySet());
        assertTrue(rolled.ladders().get("skin") >= 0 && rolled.ladders().get("skin") < 4);
        assertTrue(rolled.ladders().get("hair") >= 0 && rolled.ladders().get("hair") < 3);
    }

    /** A required slot's parameter is always answered — an unrolled shirt is a hole in a torso,
     *  not a person who chose to wear none. */
    @Test
    void aRequiredFamilyIsAlwaysChosen() {
        for (long seed = 0; seed < 40; seed++) {
            assertTrue(Genotypes.roll(seed, catalog(), CHOICES).choices().containsKey("shirt"),
                    "seed " + seed + " left the shirt unrolled");
        }
    }

    /** State is never rolled: an expression is not something you are born with. */
    @Test
    void stateIsNeverPartOfAGenotype() {
        for (long seed = 0; seed < 40; seed++) {
            Look.Composed rolled = Genotypes.roll(seed, catalog(), CHOICES);
            assertFalse(rolled.choices().containsKey("mood"), "a mood was rolled into a genotype");
            assertFalse(rolled.choices().containsKey("job"), "a job was rolled into a genotype");
        }
    }

    /** A parameter only optional slots read may go unanswered, and that is how somebody has no beard —
     *  and must sometimes be answered, or the slot could never draw at all. */
    @Test
    void anOptionalFamilyIsSometimesWornAndSometimesNot() {
        int worn = 0;
        for (long seed = 0; seed < 60; seed++) {
            if (Genotypes.roll(seed, catalog(), CHOICES).choices().containsKey("beard")) {
                worn++;
            }
        }
        assertTrue(worn > 5 && worn < 55, "beards came out " + worn + "/60 — that is not a coin");
    }

    /** Ladder draws are humped rather than flat: the middle of a tone ladder must be commoner than
     *  its ends, which is the difference between a population and a random table. */
    @Test
    void ladderPositionsClusterTowardTheMiddle() {
        int[] histogram = new int[4];
        for (long seed = 0; seed < 4000; seed++) {
            histogram[Genotypes.roll(seed, catalog(), CHOICES).ladders().get("skin")]++;
        }
        assertTrue(histogram[1] > histogram[0], "the second tone was not commoner than the first");
        assertTrue(histogram[1] + histogram[2] > histogram[0] + histogram[3],
                "the middle of the ladder was not commoner than its ends");
    }
}
