package dev.luizloyola.autarkia.mod.builder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mojang.serialization.JsonOps;
import dev.luizloyola.anima.core.brain.knowledge.AgentKnowledge;
import dev.luizloyola.anima.core.brain.knowledge.PoiKind;
import dev.luizloyola.anima.core.brain.knowledge.PoiMemory;
import dev.luizloyola.anima.core.brain.knowledge.Region;
import dev.luizloyola.anima.core.brain.knowledge.ShelterNoter;
import dev.luizloyola.anima.core.brain.sense.Pos;
import dev.luizloyola.anima.core.social.PartyId;
import dev.luizloyola.autarkia.core.board.Deconstruct;
import dev.luizloyola.autarkia.core.bp.Footprint;
import dev.luizloyola.autarkia.core.bp.Placement;
import dev.luizloyola.autarkia.core.builder.Laying;
import dev.luizloyola.anima.core.brain.act.Placing;
import dev.luizloyola.autarkia.core.builder.Section;
import dev.luizloyola.autarkia.core.builder.Structure;
import dev.luizloyola.autarkia.core.builder.Structure.Phase;
import dev.luizloyola.autarkia.core.direction.ShelterLine;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The first-night hut goes once the house stands (Luiz, 2026-10-21). Reading the plan off the
 * blueprint and the world is flown; what goes, when, and what is forgotten is here.
 */
class HutTakeDownTest {

    private static final Structure HUT = new Structure(new UUID(7, 1), ShelterLine.BLUEPRINT, 1, Map.of(),
            Map.of(), new Pos(2, 64, 1), Placement.AS_DRAWN, new Footprint(0, 0, 3, 2), new Footprint(0, -2, 3, 2),
            Phase.BUILT, 0L, "");
    private static final Structure HOUSE = new Structure(new UUID(7, 2), "autarkia:basic_wooden_house", 3, Map.of(),
            Map.of(), new Pos(20, 64, 1), Placement.AS_DRAWN, new Footprint(16, -3, 24, 5), new Footprint(14, -5, 26, 7),
            Phase.BUILT, 0L, "");
    private static final long MORNING = 1_000L;

    @Test
    void theHutGoesByDayOnceAHouseStandsThatAMemberKnows() {
        List<Structure> both = List.of(HUT, HOUSE);
        assertTrue(HutTakeDown.due(HUT, both, house -> true, MORNING));
        assertFalse(HutTakeDown.due(HUT, both, house -> false, MORNING),
                "not before somebody knows the way into the house: the night after needs somewhere");
        assertFalse(HutTakeDown.due(HUT, List.of(HUT, HOUSE.at(Phase.BUILDING, "")), house -> true, MORNING),
                "a house going up is no house");
        assertFalse(HutTakeDown.due(HUT, List.of(HUT), house -> true, MORNING), "the hut is not its own house");
        assertFalse(HutTakeDown.due(HUT, both, house -> true, 12_000L), "a hut in use at night waits for morning");
        assertTrue(HutTakeDown.due(HUT, both, house -> true, 0L), "sunrise");
        assertFalse(HutTakeDown.due(HOUSE, both, house -> true, MORNING), "the house itself never goes");
        assertFalse(HutTakeDown.due(HUT.at(Phase.TAKING_DOWN, ""), both, house -> true, MORNING),
                "posted once");
    }

    @Test
    void whatStandsOfItsPlanIsTakenDownAndNothingElse() {
        Laying wall = step("minecraft:oak_planks", new Pos(0, 65, 0));
        Laying door = new Laying(Section.WALLS, Placing.of("minecraft:oak_door", new Pos(1, 65, 0)),
                List.of(new Pos(1, 66, 0)), new Pos(1, 65, -1), 1);
        Laying broken = step("minecraft:oak_planks", new Pos(3, 65, 0));
        Set<Laying> standing = Set.of(wall, door);
        List<Deconstruct.Target> targets = HutTakeDown.targets(List.of(wall, door, broken), standing::contains,
                at -> at.equals(new Pos(1, 65, 0)) ? "minecraft:oak_door" : "minecraft:oak_planks");
        assertEquals(List.of(new Deconstruct.Target(new Pos(0, 65, 0), "minecraft:oak_planks", false),
                new Deconstruct.Target(new Pos(1, 65, 0), "minecraft:oak_door", false)), targets,
                "the door by its lower half; a block already gone is not asked for");
    }

    @Test
    void itsTakingDownIsKnownByItsBlocks() {
        Deconstruct mine = new Deconstruct(List.of(new Deconstruct.Target(new Pos(0, 65, 0), "minecraft:dirt", false)),
                HutTakeDown.WHY, 0.5);
        Deconstruct scaffold = new Deconstruct(List.of(new Deconstruct.Target(new Pos(0, 65, 0), "minecraft:dirt", false),
                new Deconstruct.Target(new Pos(15, 66, 0), "minecraft:dirt", false)), "the scaffold", 0.5);
        assertTrue(HutTakeDown.takesDown(mine, HUT));
        assertFalse(HutTakeDown.takesDown(scaffold, HUT), "a block outside its walls is another's");
    }

    @Test
    void everyWayIntoItIsForgottenAndNothingElse() {
        AgentKnowledge knowledge = new AgentKnowledge();
        knowledge.note(new PoiMemory(PoiKind.SHELTER, "1 65 0", new Pos(1, 65, 1), Region.of(new Pos(1, 65, 1)),
                3, false, 0L), 16);
        knowledge.note(new PoiMemory(PoiKind.SHELTER, "20 65 -3", new Pos(20, 65, -2), Region.of(new Pos(20, 65, -2)),
                20, false, 0L), 16);
        knowledge.note(ShelterNoter.dugIn(new Pos(1, 62, 4), new Pos(1, 64, 4), 0L), 16);
        assertEquals(1, HutTakeDown.forget(knowledge, HUT));
        assertFalse(ShelterShowing.knows(knowledge, HUT), "nobody runs for a hut that is coming down");
        assertTrue(ShelterShowing.knows(knowledge, HOUSE), "the house is kept");
        assertEquals(2, knowledge.sighted(PoiKind.SHELTER).size());
    }

    @Test
    void aHutBeingTakenDownIsNoShelterAndSurvivesARestart() {
        Structure going = HUT.at(Phase.TAKING_DOWN, "");
        assertFalse(going.stands(), "not shown to the members, nor met the shelter line by");
        assertEquals(going, StructuresData.STRUCTURE.parse(JsonOps.INSTANCE,
                StructuresData.STRUCTURE.encodeStart(JsonOps.INSTANCE, going).getOrThrow()).getOrThrow());
    }

    @Test
    void takenDownItsRecordGoes() {
        StructuresData data = new StructuresData();
        PartyId party = PartyId.of(new UUID(9, 9));
        data.add(party, HUT);
        data.add(party, HOUSE);
        assertTrue(data.remove(party, HUT.id()));
        assertEquals(List.of(HOUSE), data.of(party));
        assertFalse(data.remove(party, HUT.id()), "gone already");
    }

    private static Laying step(String item, Pos at) {
        return new Laying(Section.WALLS, Placing.of(item, at), List.of(), new Pos(at.x(), at.y(), at.z() - 1), 1);
    }
}
