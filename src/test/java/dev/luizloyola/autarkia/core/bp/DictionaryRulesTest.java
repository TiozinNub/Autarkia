package dev.luizloyola.autarkia.core.bp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * The names that broke concatenation — {@code crimson_stem}, {@code stone_brick_stairs},
 * {@code bamboo_block} — come out of plain rules, and every lookup answers at most one block.
 */
class DictionaryRulesTest {

    private final Dictionary dict = TestBlocks.dictionary();

    private Optional<String> lookup(String material, String form) {
        return dict.lookup("minecraft:" + material, form).map(Ids::brief);
    }

    @Test
    void noBlockIsClaimedTwiceForOneForm() {
        assertEquals(List.of(), TestBlocks.derived().conflicts());
    }

    @Test
    void aStrippedLogIsItsOwnFormNotAnAmbiguity() {
        assertEquals(Optional.of("oak_log"), lookup("oak", "log"));
        assertEquals(Optional.of("stripped_oak_log"), lookup("oak", "stripped_log"));
    }

    @Test
    void oneFormReachesEveryWood() {
        assertEquals(Optional.of("crimson_stem"), lookup("crimson", "log"));
        assertEquals(Optional.of("crimson_hyphae"), lookup("crimson", "wood"));
        assertEquals(Optional.of("stripped_crimson_stem"), lookup("crimson", "stripped_log"));
        assertEquals(Optional.of("bamboo_block"), lookup("bamboo", "log"));
        assertEquals(Optional.of("bamboo_planks"), lookup("bamboo", "block"));
        assertEquals(Optional.of("oak_planks"), lookup("oak", "block"));
    }

    @Test
    void theLongestWoodPrefixWins() {
        assertEquals(Optional.of("pale_oak_door"), lookup("pale_oak", "door"));
        assertEquals(Optional.of("oak_door"), lookup("oak", "door"));
    }

    @Test
    void familiesFollowVanillaNaming() {
        assertEquals(Optional.of("stone_brick_stairs"), lookup("stone_bricks", "stairs"));
        assertEquals(Optional.of("stone_brick_wall"), lookup("stone_bricks", "wall"));
        assertEquals(Optional.of("chiseled_stone_bricks"), lookup("stone_bricks", "chiseled"));
        assertEquals(Optional.of("mossy_stone_bricks"), lookup("stone_bricks", "mossy"));
        assertEquals(Optional.of("quartz_stairs"), lookup("quartz_block", "stairs"));
        assertEquals(Optional.of("chiseled_quartz_block"), lookup("quartz_block", "chiseled"));
        assertEquals(Optional.of("brick_stairs"), lookup("bricks", "stairs"));
        assertEquals(Optional.of("stone_button"), lookup("stone", "button"));
        assertEquals(Optional.of("smooth_stone"), lookup("stone", "smooth"));
        assertEquals(Optional.of("polished_granite"), lookup("granite", "polished"));
        assertEquals(Optional.of("stone"), lookup("stone", "block"));
        assertTrue(dict.material("minecraft:bamboo_mosaic").isPresent(), "a mosaic is its own family");
    }

    @Test
    void aColourFormNeedsMostColours() {
        assertEquals(Optional.of("blue_wool"), lookup("blue", "wool"));
        assertEquals(Optional.of("light_blue_bed"), lookup("light_blue", "bed"));
        assertEquals(Optional.empty(), lookup("blue", "ice"));
        assertEquals(Optional.empty(), lookup("red", "sand"));
        assertEquals(16, dict.leaves("minecraft:any_color").size());
    }

    @Test
    void theNonFlammableTagSplitsTheWoods() {
        Set<String> overworld = dict.leaves("minecraft:overworld_wood");
        assertTrue(overworld.contains("minecraft:oak"));
        assertTrue(overworld.contains("minecraft:bamboo"));
        assertFalse(overworld.contains("minecraft:crimson"));
        assertEquals(Set.of("minecraft:crimson"), dict.leaves("minecraft:nether_wood"));
        assertEquals(6, dict.leaves("minecraft:wood").size());
    }

    @Test
    void aModdedWoodJoinsByTheSameRules() {
        List<Dictionary.BlockInfo> blocks = new java.util.ArrayList<>(TestBlocks.blocks().values());
        for (String path : List.of("redwood_planks", "redwood_log", "redwood_stairs")) {
            blocks.add(new Dictionary.BlockInfo("mymod:" + path, java.util.Map.of(), java.util.Map.of(), true, 0,
                    false, "mymod:" + path));
        }
        Dictionary modded = DictionaryRules.derive(blocks, Set.of()).dictionary();
        assertEquals(Optional.of("mymod:redwood_log"), modded.lookup("mymod:redwood", "log"));
        assertTrue(modded.leaves("minecraft:overworld_wood").contains("mymod:redwood"));
    }
}
