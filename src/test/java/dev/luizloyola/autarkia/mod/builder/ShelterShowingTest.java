package dev.luizloyola.autarkia.mod.builder;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.luizloyola.anima.core.brain.knowledge.AgentKnowledge;
import dev.luizloyola.anima.core.brain.knowledge.PoiKind;
import dev.luizloyola.anima.core.brain.knowledge.PoiMemory;
import dev.luizloyola.anima.core.brain.knowledge.Region;
import dev.luizloyola.anima.core.brain.knowledge.ShelterNoter;
import dev.luizloyola.anima.core.brain.sense.Pos;
import dev.luizloyola.autarkia.core.bp.Footprint;
import dev.luizloyola.autarkia.core.bp.Placement;
import dev.luizloyola.autarkia.core.builder.Structure;
import dev.luizloyola.autarkia.core.direction.ShelterLine;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Who is shown a standing building: a member with no way into it in mind, once a minute at most.
 * Reading the door off the world and the check itself are flown.
 */
class ShelterShowingTest {

    private static final Structure HUT = new Structure(new UUID(7, 1), ShelterLine.BLUEPRINT, 1, Map.of(),
            Map.of(), new Pos(2, 64, 1), Placement.AS_DRAWN, new Footprint(0, 0, 3, 2), new Footprint(0, -2, 3, 2),
            Structure.Phase.BUILT, 0L, "");

    @Test
    void aMemberRemembersTheHutByAWayInsideItsWalls() {
        AgentKnowledge knowledge = new AgentKnowledge();
        assertFalse(ShelterShowing.knows(knowledge, HUT));
        knowledge.note(new PoiMemory(PoiKind.SHELTER, "1 65 0", new Pos(1, 65, 1), Region.of(new Pos(1, 65, 1)),
                3, false, 0L), 16);
        assertTrue(ShelterShowing.knows(knowledge, HUT));
    }

    @Test
    void aShelterElsewhereOrAPocketDugHereIsNotTheHut() {
        AgentKnowledge knowledge = new AgentKnowledge();
        knowledge.note(new PoiMemory(PoiKind.SHELTER, "40 65 0", new Pos(40, 65, 1), Region.of(new Pos(40, 65, 1)),
                3, false, 0L), 16);
        knowledge.note(ShelterNoter.dugIn(new Pos(1, 64, 1), new Pos(1, 66, 1), 0L), 16);
        assertFalse(ShelterShowing.knows(knowledge, HUT));
    }

    @Test
    void aMemberIsShownAgainOnlyAfterAMinute() {
        Map<String, Long> shown = new HashMap<>();
        assertTrue(ShelterShowing.due(shown, "a/hut", 100L));
        shown.put("a/hut", 100L);
        assertFalse(ShelterShowing.due(shown, "a/hut", 100L + ShelterShowing.AGAIN_TICKS - 1));
        assertTrue(ShelterShowing.due(shown, "a/hut", 100L + ShelterShowing.AGAIN_TICKS));
    }
}
