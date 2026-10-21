package dev.luizloyola.autarkia.mod.direction;

import dev.luizloyola.anima.core.agent.AgentId;
import dev.luizloyola.anima.core.brain.knowledge.AgentKnowledge;
import dev.luizloyola.anima.core.brain.sense.Pos;
import dev.luizloyola.anima.core.social.PartyId;
import dev.luizloyola.anima.core.social.speech.Encounter;
import dev.luizloyola.anima.core.social.speech.Handover;
import dev.luizloyola.anima.core.social.speech.SpeechActs;
import dev.luizloyola.anima.core.social.speech.Utterance;
import dev.luizloyola.anima.core.terrain.Landscape;
import dev.luizloyola.anima.core.terrain.Terrain;
import dev.luizloyola.anima.core.territory.Reason;
import dev.luizloyola.anima.mod.body.AgentBodies;
import dev.luizloyola.anima.mod.body.AgentBody;
import dev.luizloyola.anima.mod.identity.AgentDirectory;
import dev.luizloyola.anima.mod.social.PartyData;
import dev.luizloyola.anima.mod.social.SpeechEffects;
import dev.luizloyola.autarkia.core.board.Explore;
import dev.luizloyola.autarkia.core.direction.Home;
import dev.luizloyola.autarkia.core.direction.HomeJudge.Candidate;
import dev.luizloyola.autarkia.core.direction.HomeJudge.Known;
import dev.luizloyola.autarkia.core.direction.HomeJudge.Table;
import dev.luizloyola.autarkia.core.direction.HomeJudge.Want;
import dev.luizloyola.autarkia.core.direction.HomeKnob;
import dev.luizloyola.autarkia.core.direction.HomeLooking;
import dev.luizloyola.autarkia.core.direction.HomeSearch;
import dev.luizloyola.autarkia.core.person.Joining;
import dev.luizloyola.autarkia.mod.board.PartyBoards;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import org.jspecify.annotations.Nullable;

/**
 * The world's half of a scout's stop: the plots judged around it ({@link HomeChooser}), and for each
 * of the eight headings the open land ahead, where a leg that way would end, and which wants lie
 * that way. And the claim, made after reading the ground again. Overworld only, as HOME is.
 */
public final class HomeLooks implements HomeLooking {

    /** The ring whose open land draws a heading: past where the stop's own plots are judged. */
    private static final int RING_INNER = 40;
    private static final int RING_OUTER = 64;

    /** A want counts as lying a way when it is farther out than this, and within the read's reach. */
    private static final int AHEAD_FROM = 32;
    private static final int AHEAD_TO = 128;

    /** How far round a leg's end its standable column is looked for. */
    private static final int LEG_SLACK = 16;

    private static volatile @Nullable MinecraftServer live;

    private HomeLooks() {
    }

    public static void init() {
        ServerLifecycleEvents.SERVER_STARTED.register(server -> live = server);
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> live = null);
        Explore.install(new HomeLooks());
        // The leader's word on a found place (group-up spec, decision 14).
        SpeechEffects.on(SpeechActs.SETTLE_HERE.key(), (server, e, index) -> answered(server, e, index, true));
        SpeechEffects.on(SpeechActs.KEEP_LOOKING.key(), (server, e, index) -> answered(server, e, index, false));
    }

    /** The finder's party's search, told by the leader what to do with the place it was shown. */
    private static void answered(MinecraftServer server, Encounter e, int index, boolean settle) {
        Optional<Utterance> asked = Handover.answered(e, index)
                .filter(u -> u.act().equals(SpeechActs.SUGGEST_HOME.key()));
        if (asked.isEmpty() || asked.get().author() == null) {
            return;
        }
        AgentId finder = asked.get().author();
        AgentBody body = AgentBodies.findLoaded(server, finder);
        explore(server, finder).ifPresent(explore -> {
            if (!settle) {
                explore.leaderDeclines();
            } else if (body != null) {
                explore.leaderSettles(body.brain().knowledge());
            }
            PartyBoards.touch(server);
        });
    }

    private static Optional<Explore> explore(MinecraftServer server, AgentId member) {
        return PartyData.get(server).currentPartyOf(member).flatMap(party -> PartyBoards.of(server, party)
                .projects().stream().filter(Explore.class::isInstance).map(Explore.class::cast).findFirst());
    }

    /** A party led by a player waits on that player for HOME; one led by a settler does not. */
    @Override
    public Optional<AgentId> waitingLeader(PartyId party) {
        MinecraftServer server = live;
        if (server == null) {
            return Optional.empty();
        }
        return PartyData.get(server).leader(party)
                .filter(leader -> AgentDirectory.of(server).identity(leader).isEmpty());
    }

    @Override
    public Optional<Pos> suggestion(AgentId self, AgentId leader) {
        MinecraftServer server = live;
        if (server == null) {
            return Optional.empty();
        }
        Optional<PartyId> party = PartyData.get(server).currentPartyOf(self);
        if (party.isEmpty() || !waitingLeader(party.get()).filter(leader::equals).isPresent()) {
            return Optional.empty();
        }
        return explore(server, self).flatMap(Explore::suggestion);
    }

    @Override
    public void scouting(PartyId party, AgentId scout) {
        MinecraftServer server = live;
        if (server == null) {
            return;
        }
        PartyData parties = PartyData.get(server);
        if (Joining.scoutLeads(parties.members(party).size(), parties.leader(party).isPresent())
                && parties.currentPartyOf(scout).filter(party::equals).isPresent()) {
            parties.lead(scout);
        }
    }

    @Override
    public Optional<Look> look(Pos at, PartyId party, AgentKnowledge knowledge) {
        MinecraftServer server = live;
        if (server == null) {
            return Optional.empty();
        }
        ServerLevel level = server.overworld();
        HomeChooser.Choice choice = HomeChooser.choose(level, new BlockPos(at.x(), at.y(), at.z()),
                HomeKnob.READ_RADIUS.i(), party, knowledge);
        return Optional.of(new Look(choice.judgement(), ways(at, choice)));
    }

    @Override
    public boolean claim(PartyId party, Candidate plot, AgentKnowledge knowledge) {
        MinecraftServer server = live;
        if (server == null) {
            return false;
        }
        HomeChooser.Choice choice = HomeChooser.choose(server.overworld(),
                new BlockPos(plot.x(), plot.y(), plot.z()), 0, party, knowledge);
        boolean allowed = choice.judgement().ranked().stream()
                .anyMatch(again -> again.x() == plot.x() && again.z() == plot.z());
        if (!allowed) {
            return false;
        }
        Pos centre = new Pos(plot.x(), plot.y() + 1, plot.z());
        return Directions.settle(server, party, Home.square(centre, plot.size() / 2),
                Reason.of(Reason.Kind.FOUND, "settled at (" + centre.x() + ", " + centre.y() + ", " + centre.z() + "), worth " + Math.round(plot.value())))
                .granted();
    }

    /** The eight headings from {@code at}, in {@link HomeSearch}'s order. */
    private static List<Way> ways(Pos at, HomeChooser.Choice choice) {
        Terrain terrain = choice.land().terrain();
        Table table = choice.table();
        int[] open = new int[HomeSearch.HEADINGS];
        int[] known = new int[HomeSearch.HEADINGS];
        List<Set<Want>> ahead = new ArrayList<>();
        for (int k = 0; k < HomeSearch.HEADINGS; k++) {
            ahead.add(EnumSet.noneOf(Want.class));
        }
        for (int dz = -AHEAD_TO; dz <= AHEAD_TO; dz++) {
            for (int dx = -AHEAD_TO; dx <= AHEAD_TO; dx++) {
                double distance = Math.hypot(dx, dz);
                int x = at.x() + dx;
                int z = at.z() + dz;
                if (distance <= AHEAD_FROM || distance > AHEAD_TO
                        || !inside(terrain, x, z) || terrain.kind(x, z) == Terrain.Kind.UNKNOWN) {
                    continue;
                }
                int k = sector(dx, dz);
                if (distance >= RING_INNER && distance <= RING_OUTER) {
                    known[k]++;
                    if (terrain.flat(x, z)) {
                        open[k]++;
                    }
                }
                if (distance > AHEAD_FROM && terrain.kind(x, z) == Terrain.Kind.FLUID) {
                    Landscape land = choice.land();
                    if (!terrain.lava(x, z)
                            && land.toFluid(Landscape.Fluid.WATER, table.water().min(), 1, x, z) == 0) {
                        ahead.get(k).add(Want.WATER);
                    } else if (terrain.lava(x, z)
                            && land.toFluid(Landscape.Fluid.LAVA, table.lava().min(), 1, x, z) == 0) {
                        ahead.get(k).add(Want.LAVA);
                    }
                }
            }
        }
        for (Map.Entry<Want, List<Known>> entry : choice.known().entrySet()) {
            for (Known thing : entry.getValue()) {
                int dx = thing.x() - at.x();
                int dz = thing.z() - at.z();
                if (Math.hypot(dx, dz) > AHEAD_FROM) {
                    ahead.get(sector(dx, dz)).add(entry.getKey());
                }
            }
        }
        List<Way> ways = new ArrayList<>(HomeSearch.HEADINGS);
        for (int k = 0; k < HomeSearch.HEADINGS; k++) {
            ways.add(new Way(known[k] == 0 ? 0 : (double) open[k] / known[k], legEnd(at, k, terrain),
                    ahead.get(k)));
        }
        return ways;
    }

    /** The heading whose sector holds this offset: yaw 0 faces +Z, as the headings do. */
    private static int sector(int dx, int dz) {
        double yaw = Math.toDegrees(Math.atan2(-dx, dz));
        return Math.floorMod((int) Math.round(yaw / 45.0), HomeSearch.HEADINGS);
    }

    /**
     * A standable column near a leg's length along heading {@code k}: dry, known and unused, the
     * nearest to the point, trying shorter legs when there is none.
     */
    private static @Nullable Pos legEnd(Pos at, int k, Terrain terrain) {
        double[] d = HomeSearch.direction(k);
        int length = HomeKnob.LEG_LENGTH.i();
        for (int reach = length; reach >= length / 2; reach -= 8) {
            int tx = at.x() + (int) Math.round(d[0] * reach);
            int tz = at.z() + (int) Math.round(d[1] * reach);
            Pos best = null;
            double bestDistance = Double.MAX_VALUE;
            for (int z = tz - LEG_SLACK; z <= tz + LEG_SLACK; z++) {
                for (int x = tx - LEG_SLACK; x <= tx + LEG_SLACK; x++) {
                    if (!inside(terrain, x, z)) {
                        continue;
                    }
                    Terrain.Kind kind = terrain.kind(x, z);
                    if (kind == Terrain.Kind.UNKNOWN || kind == Terrain.Kind.FLUID
                            || kind == Terrain.Kind.USED) {
                        continue;
                    }
                    double distance = Math.hypot(x - tx, z - tz);
                    if (distance < bestDistance) {
                        bestDistance = distance;
                        best = new Pos(x, terrain.ground(x, z) + 1, z);
                    }
                }
            }
            if (best != null) {
                return best;
            }
        }
        return null;
    }

    private static boolean inside(Terrain terrain, int x, int z) {
        return x >= terrain.minX() && z >= terrain.minZ() && x < terrain.minX() + terrain.width()
                && z < terrain.minZ() + terrain.depth();
    }
}
