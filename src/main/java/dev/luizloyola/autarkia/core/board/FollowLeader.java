package dev.luizloyola.autarkia.core.board;

import dev.luizloyola.anima.core.agent.AgentId;
import dev.luizloyola.anima.core.brain.BrainContext;
import dev.luizloyola.anima.core.brain.board.WorkItem;
import dev.luizloyola.anima.core.brain.history.Deed;
import dev.luizloyola.anima.core.brain.sense.BeingId;
import dev.luizloyola.anima.core.brain.task.Follow;
import dev.luizloyola.anima.core.brain.task.Task;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * The party walks with its leader to choose a home (group-up spec, decision 14): every member told
 * "follow me" keeps with them until the leader names the spot. Keeping up ends each time the leader
 * stands still, and is taken up again when they move off.
 */
public final class FollowLeader implements PartyProject {

    public static final String FLAVOUR = "follow_leader";

    /** After catching up with a leader standing still, before keeping with them again. */
    static final int CAUGHT_UP_PAUSE = 40;
    /** After losing sight of them. */
    static final int LOST_PAUSE = 200;

    private final AgentId leader;
    private final double priority;
    private final Map<AgentId, Keep> keeps = new LinkedHashMap<>();
    private final Map<AgentId, Long> pausedUntil = new LinkedHashMap<>();
    private boolean ended;

    public FollowLeader(AgentId leader, List<AgentId> followers, double priority) {
        this.leader = Objects.requireNonNull(leader, "leader");
        this.priority = priority;
        followers.forEach(who -> keeps.put(who, new Keep(who)));
    }

    public AgentId leader() {
        return leader;
    }

    /** The leader named the spot, or went: nobody follows any more. */
    public void end() {
        ended = true;
    }

    @Override
    public double priority() {
        return priority;
    }

    @Override
    public List<WorkItem> open() {
        return ended ? List.of() : List.copyOf(keeps.values());
    }

    @Override
    public boolean offerableTo(WorkItem item, AgentId asker, BrainContext ctx) {
        Long until = pausedUntil.get(asker);
        return item instanceof Keep keep && keep.who.equals(asker)
                && (until == null || ctx.percepts().time() >= until);
    }

    @Override
    public boolean finished() {
        return ended;
    }

    @Override
    public void tick(long now) {
    }

    @Override
    public void completed(WorkItem item, BrainContext ctx) {
        pause(item, ctx.percepts().time() + CAUGHT_UP_PAUSE);
    }

    @Override
    public void failed(WorkItem item, BrainContext ctx) {
        pause(item, ctx.percepts().time() + LOST_PAUSE);
    }

    private void pause(WorkItem item, long until) {
        if (item instanceof Keep keep) {
            pausedUntil.put(keep.who, until);
        }
    }

    @Override
    public Optional<WorkKey> keyOf(WorkItem item) {
        return item instanceof Keep keep ? Optional.of(new WorkKey.ForMember(FLAVOUR, keep.who)) : Optional.empty();
    }

    @Override
    public Optional<WorkItem> itemFor(WorkKey key) {
        return !ended && key instanceof WorkKey.ForMember member && member.flavour().equals(FLAVOUR)
                ? Optional.ofNullable(keeps.get(member.who())) : Optional.empty();
    }

    @Override
    public String describe() {
        return "keep with the leader — " + keeps.size() + " following" + (ended ? ", ended" : "");
    }

    private final class Keep implements WorkItem {
        private final AgentId who;

        private Keep(AgentId who) {
            this.who = who;
        }

        @Override
        public double priority() {
            return priority;
        }

        @Override
        public Task root() {
            return new Follow(BeingId.of(leader), null);
        }

        @Override
        public Deed doing() {
            return Deed.of(WorkDoings.EXPLORING);
        }

        @Override
        public String describe() {
            return "keep with the leader";
        }
    }

    public record State(AgentId leader, List<AgentId> followers, double priority, List<Gather.Cooldown> paused,
                        boolean ended) implements ProjectState {
        public State {
            followers = List.copyOf(followers);
            paused = List.copyOf(paused);
        }

        @Override
        public String type() {
            return "follow_leader";
        }
    }

    @Override
    public State snapshot() {
        List<Gather.Cooldown> paused = new ArrayList<>();
        pausedUntil.forEach((who, until) -> paused.add(new Gather.Cooldown(who, until)));
        return new State(leader, new ArrayList<>(keeps.keySet()), priority, paused, ended);
    }

    public static final ProjectType TYPE = new ProjectType() {
        @Override
        public String id() {
            return "follow_leader";
        }

        @Override
        public Optional<FollowLeader> restore(ProjectState state, long now) {
            if (!(state instanceof State s)) {
                return Optional.empty();
            }
            FollowLeader project = new FollowLeader(s.leader(), s.followers(), s.priority());
            s.paused().forEach(cooldown -> project.pausedUntil.put(cooldown.who(), cooldown.retryAfter()));
            project.ended = s.ended();
            return Optional.of(project);
        }
    };
}
