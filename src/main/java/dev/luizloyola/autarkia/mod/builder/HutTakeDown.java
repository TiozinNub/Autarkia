package dev.luizloyola.autarkia.mod.builder;

import dev.luizloyola.anima.compat.WorldClocks;
import dev.luizloyola.anima.compat.sense.LevelProbe;
import dev.luizloyola.anima.core.agent.AgentId;
import dev.luizloyola.anima.core.brain.instinct.NightShelterInstinct;
import dev.luizloyola.anima.core.brain.knowledge.AgentKnowledge;
import dev.luizloyola.anima.core.brain.knowledge.KnowledgeRegistry;
import dev.luizloyola.anima.core.brain.knowledge.PoiKind;
import dev.luizloyola.anima.core.brain.knowledge.PoiMemory;
import dev.luizloyola.anima.core.brain.knowledge.ShelterNoter;
import dev.luizloyola.anima.core.brain.sense.Pos;
import dev.luizloyola.anima.core.social.PartyId;
import dev.luizloyola.anima.mod.brain.KnowledgeData;
import dev.luizloyola.anima.mod.social.PartyData;
import dev.luizloyola.autarkia.core.board.Build;
import dev.luizloyola.autarkia.core.board.Deconstruct;
import dev.luizloyola.autarkia.core.board.Project;
import dev.luizloyola.autarkia.core.builder.Laying;
import dev.luizloyola.autarkia.core.builder.Structure;
import dev.luizloyola.autarkia.core.builder.Structure.Phase;
import dev.luizloyola.autarkia.mod.board.PartyBoards;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.function.Predicate;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import org.jspecify.annotations.Nullable;

/**
 * The first-night hut taken down once the house stands (Luiz, 2026-10-21): each of its blocks
 * still standing as its plan laid them goes to a {@link Deconstruct}, whose drops are gleaned and
 * put away in the stores as any work's, and every member forgets it as a shelter. Only by day: at
 * night nobody works, and a member may be sleeping in it.
 *
 * <p>The record reads {@link Phase#TAKING_DOWN} while that is on the board, so a restart carries
 * on, and goes once the taking down is done.
 */
final class HutTakeDown {

    /** As a scaffold's. */
    private static final double PRIORITY = 0.5;

    static final String WHY = "the first-night hut, the house stands";

    private HutTakeDown() {
    }

    /**
     * Whether {@code hut} goes now: a quick building standing beside a house of the party's that a
     * member knows a way into, so the night after has somewhere to go, and by day.
     */
    static boolean due(Structure hut, List<Structure> structures, Predicate<Structure> known, long timeOfDay) {
        if (!hut.quick() || hut.phase() != Phase.BUILT || timeOfDay >= NightShelterInstinct.SUNSET) {
            return false;
        }
        for (Structure house : structures) {
            if (!house.quick() && house.stands() && known.test(house)) {
                return true;
            }
        }
        return false;
    }

    /** Each step of its plan that stands as laid, by the block standing there; a door by its lower half. */
    static List<Deconstruct.Target> targets(List<Laying> order, Predicate<Laying> standing, Function<Pos, String> idAt) {
        List<Deconstruct.Target> targets = new ArrayList<>();
        for (Laying step : order) {
            if (standing.test(step)) {
                targets.add(new Deconstruct.Target(step.cell(), idAt.apply(step.cell()), false));
            }
        }
        return targets;
    }

    /** Whether this taking down is the hut's: every block of it inside its walls. */
    static boolean takesDown(Deconstruct project, Structure hut) {
        return !project.targets().isEmpty() && project.targets().stream().allMatch(t -> hut.holds(t.at()));
    }

    /** Forgets every way into the hut this body remembers; how many. */
    static int forget(AgentKnowledge knowledge, Structure hut) {
        int forgotten = 0;
        for (PoiMemory way : List.copyOf(knowledge.sighted(PoiKind.SHELTER))) {
            if (!ShelterNoter.dug(way) && hut.holds(way.anchor())) {
                knowledge.forget(PoiKind.SHELTER, way.anchor());
                forgotten++;
            }
        }
        return forgotten;
    }

    /** {@link #due}, read off the world and the members' memories. */
    static boolean due(MinecraftServer server, PartyId party, Structure hut, List<Structure> structures) {
        ServerLevel level = server.overworld();
        if (!hut.quick() || !WorldClocks.keepsHours(level)) {
            return false;
        }
        KnowledgeRegistry known = KnowledgeData.get(server).registry();
        List<AgentId> members = PartyData.get(server).members(party);
        return due(hut, structures, house -> members.stream()
                .anyMatch(member -> ShelterShowing.knows(known.forPerson(member), house)), WorldClocks.timeOfDay(level));
    }

    /**
     * Posts its taking down and has every member forget it; null when nothing of it stands any
     * more, so the record goes at once.
     */
    static @Nullable Structure takeDown(MinecraftServer server, PartyId party, Structure hut) {
        ServerLevel level = server.overworld();
        Build plan = Builds.of(level, hut, new ArrayList<>());
        if (plan == null) {
            return hut; // its blueprint gone: nothing says which blocks are its
        }
        forgetIt(server, party, hut);
        LevelProbe probe = new LevelProbe(level);
        List<Deconstruct.Target> targets = targets(plan.order(),
                step -> step.standsAs(probe.idAt(step.cell().x(), step.cell().y(), step.cell().z()),
                        probe.stateAt(step.cell().x(), step.cell().y(), step.cell().z())),
                at -> probe.idAt(at.x(), at.y(), at.z()));
        if (targets.isEmpty()) {
            return null;
        }
        PartyBoards.of(server, party).post(new Deconstruct(targets, WHY, PRIORITY));
        PartyBoards.touch(server);
        Structures.journal(server, party, "taking down the hut, the house stands: " + targets.size() + " blocks");
        return hut.at(Phase.TAKING_DOWN, "");
    }

    /** A taking down gone from the board unfinished, or never posted: it is posted again when next due. */
    static Structure stillTakingDown(MinecraftServer server, PartyId party, Structure hut) {
        for (Project project : PartyBoards.of(server, party).projects()) {
            if (project instanceof Deconstruct deconstruct && takesDown(deconstruct, hut)) {
                return hut;
            }
        }
        return hut.at(Phase.BUILT, "");
    }

    /** Every member forgets it, loaded or not: the memories are kept apart from the bodies. */
    static void forgetIt(MinecraftServer server, PartyId party, Structure hut) {
        KnowledgeRegistry known = KnowledgeData.get(server).registry();
        for (AgentId member : PartyData.get(server).members(party)) {
            forget(known.forPerson(member), hut);
        }
    }
}
