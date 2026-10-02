package dev.luizloyola.autarkia.core.builder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.luizloyola.anima.core.brain.sense.Pos;
import dev.luizloyola.autarkia.core.bp.Blueprint;
import dev.luizloyola.autarkia.core.bp.Blueprint.Outcome;
import dev.luizloyola.autarkia.core.bp.BuildPlan;
import dev.luizloyola.autarkia.core.bp.Placement;
import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** A standing house grows by one group's variant, for the station it lacks, and only its diff is built. */
class GrowthTest {

    private static final String CHEST = "minecraft:chest";
    private static final String FURNACE = "minecraft:furnace";
    private static final String TABLE = "minecraft:crafting_table";

    private static final Map<String, String> LV1 = Map.of("base", "lv1", "beds", "none", "attic", "none");

    private static Blueprint house;

    @BeforeAll
    static void house() throws IOException {
        house = BuildOrderTest.bind(BuildOrderTest.read("/data/autarkia/autarkia/blueprint/basic_wooden_house.bp"));
    }

    private static final Function<Map<String, String>, Optional<Growth.Holds>> HOLDS = variants -> {
        BuildPlan plan = BuildOrderTest.plan(house, variants);
        Map<String, Integer> stations = new HashMap<>();
        int[] blocks = {0};
        plan.forEach(Placement.AS_DRAWN, (dx, layer, dz, kind, state) -> {
            if (kind == BuildPlan.CellKind.BLOCK && state != null) {
                blocks[0]++;
                if (List.of(CHEST, FURNACE, TABLE).contains(state.block())) {
                    stations.merge(state.block(), 1, Integer::sum);
                }
            }
        });
        return Optional.of(new Growth.Holds(stations, blocks[0]));
    };

    private static Map<String, String> with(String base) {
        Map<String, String> variants = new HashMap<>(LV1);
        variants.put("base", base);
        return variants;
    }

    @Test
    void roomGrowsTheBaseByItsChestsAndNothingElse() {
        assertEquals(Optional.of(with("lv2")), Growth.choose(house.variants(), LV1, CHEST, HOLDS),
                "lv2's three double chests, the least that adds one: lv3 adds a furnace too");
    }

    @Test
    void aFurnaceGrowsTheBaseToLevelThree() {
        assertEquals(Optional.of(with("lv3")), Growth.choose(house.variants(), LV1, FURNACE, HOLDS));
        assertEquals(Optional.of(with("lv3")), Growth.choose(house.variants(), with("lv2"), FURNACE, HOLDS));
    }

    @Test
    void aHouseAtItsLargestHasNoGrowthLeft() {
        assertEquals(Optional.empty(), Growth.choose(house.variants(), with("lv3"), FURNACE, HOLDS));
        assertEquals(Optional.empty(), Growth.choose(house.variants(), with("lv2"), CHEST, HOLDS),
                "lv3 holds the same chests as lv2");
        assertEquals(Optional.empty(), Growth.choose(house.variants(), with("lv3"), CHEST, HOLDS),
                "and dropping a level loses the furnace");
    }

    @Test
    void anUnnamedOptionalGroupIsReadAsNone() {
        assertEquals(Optional.of(with("lv3")), Growth.choose(house.variants(), Map.of("base", "lv1"), FURNACE, HOLDS));
    }

    @Test
    void theDiffIsTheNewChestsAndTheFurnaceAndNothingOfTheHouse() {
        Pos anchor = new Pos(100, 64, 200);
        Map<Pos, Outcome> changed = Growth.changed(BuildOrderTest.plan(house, LV1),
                BuildOrderTest.plan(house, with("lv3")), Placement.AS_DRAWN, anchor);

        Map<String, Integer> blocks = new HashMap<>();
        changed.values().forEach(state -> blocks.merge(state == null ? "air" : state.block(), 1, Integer::sum));
        assertEquals(Map.of(CHEST, 6, FURNACE, 1), blocks,
                "a double chest beside the first, one stacked on each, and the furnace on the table");
        changed.keySet().forEach(at -> assertTrue(at.y() == anchor.y() + 2 || at.y() == anchor.y() + 3, at::toString));
    }

    @Test
    void shrinkingClearsWhatTheSmallerSelectionLeavesOut() {
        Map<Pos, Outcome> changed = Growth.changed(BuildOrderTest.plan(house, with("lv3")),
                BuildOrderTest.plan(house, LV1), Placement.AS_DRAWN, new Pos(0, 0, 0));

        assertEquals(7, changed.size());
        changed.values().forEach(state -> assertNull(state, "every cell lv1 leaves out is cleared"));
    }
}
