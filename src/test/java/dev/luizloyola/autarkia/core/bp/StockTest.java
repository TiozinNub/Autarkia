package dev.luizloyola.autarkia.core.bp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.luizloyola.autarkia.core.bp.BpCompiler.Compiled;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * A house's wood is bound from what the party has, and bound again when nobody can get it — the
 * random binding waited on dark oak in a jungle for good (2026-10-01).
 */
class StockTest {

    private static final Dictionary DICT = TestBlocks.dictionary();
    private static final Map<String, String> LV1 = Map.of("base", "lv1", "beds", "none", "attic", "none");
    private static Blueprint house;

    @BeforeAll
    static void load() throws IOException {
        try (InputStream in = StockTest.class.getResourceAsStream(
                "/data/autarkia/autarkia/blueprint/basic_wooden_house.bp")) {
            assertNotNull(in);
            Compiled compiled = BpCompiler.compile("t", new String(in.readAllBytes(), StandardCharsets.UTF_8), DICT);
            assertTrue(compiled.ok(), compiled.diagnostics()::toString);
            house = compiled.blueprint();
        }
    }

    private static Stock stock(Map<String, Integer> held, Map<String, Integer> growing) {
        return Stock.of(DICT, held, growing);
    }

    private static Map<Integer, String> bindings(Stock stock, Map<Integer, String> pins, Chooser otherwise) {
        Diagnostics out = new Diagnostics();
        BuildPlan plan = Planner.plan(house, DICT, Support.solidCubes(DICT), pins, LV1,
                Chooser.stocked(stock, otherwise), new Random(1), out);
        assertNotNull(plan, out.list()::toString);
        return plan.bindings();
    }

    /** Always the last way, and says what it was asked. */
    private static Chooser last(List<String> asked) {
        return (what, choices) -> {
            asked.add(what);
            return choices.size() - 1;
        };
    }

    @Test
    void theWoodHeldMostIsTheHouses() {
        List<String> asked = new ArrayList<>();
        Map<Integer, String> bound = bindings(stock(Map.of("minecraft:spruce_log", 500, "minecraft:oak_planks", 30),
                Map.of("minecraft:birch_log", 900)), Map.of(), last(asked));
        assertEquals("spruce", bound.get(1));
        assertEquals("spruce", bound.get(2), "logs count for every form of their wood");
        assertEquals(List.of("slot 3"), asked, "only the bed's colour, which nobody holds, is left to chance");
    }

    @Test
    void withNothingHeldTheTreesTheyRememberDecide() {
        Map<Integer, String> bound = bindings(stock(Map.of(), Map.of("minecraft:birch_log", 120)), Map.of(),
                last(new ArrayList<>()));
        assertEquals("birch", bound.get(1));
        assertEquals("birch", bound.get(2));
    }

    @Test
    void aFewLogsFromABushLoseToAWood() {
        Map<Integer, String> bound = bindings(stock(Map.of("minecraft:oak_log", 2),
                Map.of("minecraft:spruce_log", 300)), Map.of(), last(new ArrayList<>()));
        assertEquals("spruce", bound.get(1), "two oak logs are not enough for the walls");
    }

    @Test
    void withNothingKnownTheFallbackChooses() {
        List<String> asked = new ArrayList<>();
        Map<Integer, String> bound = bindings(Stock.NONE, Map.of(), last(asked));
        assertEquals(List.of("slot 1", "slot 2", "slot 3"), asked);
        assertTrue(DICT.leaves("minecraft:overworld_wood").contains(Ids.qualify(bound.get(1))));
    }

    @Test
    void anOperatorPinWinsOverTheStock() {
        Map<Integer, String> bound = bindings(stock(Map.of("minecraft:spruce_log", 500), Map.of()),
                Map.of(1, "oak"), last(new ArrayList<>()));
        assertEquals("oak", bound.get(1));
        assertEquals("spruce", bound.get(2), "what the pin leaves still goes to the stock");
    }

    @Test
    void aVariantSelectionIsNeverTheStocks() {
        List<String> asked = new ArrayList<>();
        Diagnostics out = new Diagnostics();
        assertNotNull(Planner.plan(house, DICT, Support.solidCubes(DICT), Map.of(), Map.of(),
                Chooser.stocked(stock(Map.of("minecraft:white_wool", 64), Map.of()), last(asked)), new Random(1),
                out), out.list()::toString);
        assertEquals(Chooser.VARIANTS, asked.get(0));
    }

    // ── bound again ─────────────────────────────────────────────────────────────────────────

    private static final Map<Integer, String> BIRCH = Map.of(1, "birch", 2, "oak", 3, "white");
    private static final Map<String, Integer> LEFT = Map.of("minecraft:birch_log", 40, "minecraft:birch_door", 1);

    @Test
    void aSlotNobodyCanGetIsBoundToWhatTheyHave() {
        Rebind.Rebound rebound = Rebind.of(house, DICT, BIRCH, Set.of("minecraft:birch_log"), Set.of(), LEFT,
                stock(Map.of("minecraft:spruce_log", 500), Map.of())).orElseThrow();
        assertEquals(1, rebound.slot());
        assertEquals("birch", rebound.from());
        assertEquals("spruce", rebound.to());
        assertEquals(Map.of(1, "spruce", 2, "oak", 3, "white"), rebound.bindings());

        Diagnostics out = new Diagnostics();
        BuildPlan plan = Planner.plan(house, DICT, Support.solidCubes(DICT), rebound.bindings(), LV1,
                (what, choices) -> 0, new Random(1), out);
        assertNotNull(plan, out.list()::toString);
        assertEquals(rebound.bindings(), plan.bindings(), "the new binding plans as a pin");
    }

    @Test
    void aSlotAlreadyStartedIsNotBoundAgain() {
        assertEquals(Optional.empty(), Rebind.of(house, DICT, BIRCH, Set.of("minecraft:birch_log"),
                Set.of("minecraft:birch_door"), LEFT, stock(Map.of("minecraft:spruce_log", 500), Map.of())));
    }

    @Test
    void aMaterialThePartyKnowsOfIsKept() {
        assertEquals(Optional.empty(), Rebind.of(house, DICT, BIRCH, Set.of("minecraft:birch_log"), Set.of(), LEFT,
                stock(Map.of("minecraft:spruce_log", 500), Map.of("minecraft:birch_log", 20))));
    }

    @Test
    void withNothingElseKnownTheSlotWaits() {
        assertEquals(Optional.empty(), Rebind.of(house, DICT, BIRCH, Set.of("minecraft:birch_log"), Set.of(), LEFT,
                Stock.NONE));
    }

    @Test
    void anotherWoodNobodyCanGetIsPassedOverToo() {
        Rebind.Rebound rebound = Rebind.of(house, DICT, BIRCH, Set.of("minecraft:birch_log", "minecraft:spruce_log"),
                Set.of(), LEFT, stock(Map.of("minecraft:spruce_log", 500, "minecraft:oak_log", 100), Map.of()))
                .orElseThrow();
        assertEquals("oak", rebound.to());
    }
}
