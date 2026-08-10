package dev.luizloyola.autarkia.core.board;

import dev.luizloyola.anima.core.agent.AgentId;
import dev.luizloyola.anima.core.brain.board.WorkItem;
import dev.luizloyola.anima.core.social.PartyId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * One party's board — where a group's work is posted, reached by every member through their own
 * {@link Board#viewFor view}.
 *
 * <p>It hangs off a {@link PartyId} rather than off any member, so the work outlives members coming
 * and going and the whole party being unloaded. It ticks server-side and entity-free.
 */
public final class PartyBoard extends Board {

    private final PartyId party;

    public PartyBoard(PartyId party) {
        this.party = party;
    }

    public PartyId party() {
        return party;
    }

    @Override
    public String label() {
        return "party";
    }

    /**
     * One beat of the board's own entity-free thinking, run by the server-side host on a staggered
     * cadence with no agent's context. Projects think first, then lapsed holds are swept and
     * anything satisfied is closed — the beat that frees a shared errand when the member holding it
     * died, unloaded or was pulled away, with no member ticking and no death hook anywhere.
     *
     * <p>A project that is not a {@link PartyProject} is carried but never ticked.
     */
    public void tick(long now) {
        for (Project project : projects()) {
            if (project instanceof PartyProject party) {
                party.tick(now);
            }
        }
        expire(now);
        closeFinished();
    }

    /**
     * Who is holding which of this project's errands right now, by durable name.
     *
     * <p>Keyed rather than by item so a caller can match a hold against project state it already
     * has — a debug view knows anchors, not the item objects the board leases by.
     */
    public Map<WorkKey, AgentId> holdsOn(PartyProject project, long now) {
        Map<WorkKey, AgentId> out = new java.util.LinkedHashMap<>();
        for (Map.Entry<WorkItem, AgentId> hold : held(now)) {
            project.keyOf(hold.getKey()).ifPresent(key -> out.put(key, hold.getValue()));
        }
        return out;
    }

    // ── continuity ───────────────────────────────────────────────────────────────────────────

    /**
     * One posted project and every hold on it — the store's row shape.
     *
     * <p>Holds travel with the project because a {@link WorkKey} names an item <em>within</em> the
     * project that minted it: two projects clearing overlapping boxes would write the same key.
     */
    public record Row(ClearArea.State project, List<Hold> holds) {
    }

    /** Who was holding which item when the world stopped. */
    public record Hold(WorkKey key, AgentId who) {
    }

    /**
     * Every party project posted here, with its holds, in post order.
     *
     * <p>Only {@link ClearArea} for now, and typed as such deliberately: a second party project
     * type will arrive with its own row shape and its own codec.
     */
    public List<Row> snapshot(long now) {
        List<Map.Entry<WorkItem, AgentId>> live = held(now);
        List<Row> rows = new ArrayList<>();
        for (Project project : projects()) {
            if (!(project instanceof ClearArea area)) {
                continue;
            }
            List<Hold> holds = new ArrayList<>();
            for (Map.Entry<WorkItem, AgentId> hold : live) {
                area.keyOf(hold.getKey()).ifPresent(key -> holds.add(new Hold(key, hold.getValue())));
            }
            rows.add(new Row(area.snapshot(), List.copyOf(holds)));
        }
        return List.copyOf(rows);
    }

    /**
     * Posts every saved project back and gives each member their errand back.
     *
     * <p>Through {@link Board#reclaim}, never {@code claim}: re-<em>taking</em> puts an errand
     * through scoring and can hand it to somebody else. Ticks do not pass while a server is down, so
     * a hold was never near expiring. A hold whose item is gone is dropped.
     *
     * @return how many saved projects could not be rebuilt, because no {@link Clearing} in this
     *         build answers to their id — never silently zero
     */
    public int restore(List<Row> rows, long now) {
        int unknown = 0;
        for (Row row : rows) {
            Optional<ClearArea> rebuilt = ClearArea.restore(row.project(), now);
            if (rebuilt.isEmpty()) {
                unknown++;
                continue;
            }
            ClearArea project = rebuilt.get();
            post(project);
            for (Hold hold : row.holds()) {
                project.itemFor(hold.key()).ifPresent(item -> {
                    reclaim(item, hold.who(), now);
                    // `reclaim` skips the bidding `claim` does, and that includes telling the
                    // project — which would otherwise think the errand free and withdraw it out
                    // from under the member still walking to it.
                    project.claimed(item);
                });
            }
        }
        return unknown;
    }
}
