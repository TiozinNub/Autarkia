package dev.luizloyola.autarkia.core.board;

import dev.luizloyola.anima.core.agent.AgentId;
import dev.luizloyola.anima.core.brain.BrainContext;
import dev.luizloyola.anima.core.brain.WorkToleranceCurve;
import dev.luizloyola.anima.core.brain.board.WorkItem;
import dev.luizloyola.anima.core.brain.task.ObtainItem;
import dev.luizloyola.anima.core.brain.task.Producers;
import dev.luizloyola.anima.core.inv.ItemSpec;
import dev.luizloyola.anima.core.log.Category;
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
     * <p>A project that is not a {@link PartyProject} is carried but never ticked. Returns the
     * projects it closed finished, which the host passes on the same tick — the one moment a
     * Direction can learn its work was done (see {@code Evolution.collect}).
     */
    public List<Project> tick(long now) {
        for (Project project : projects()) {
            if (project instanceof PartyProject party) {
                party.tick(now);
            }
        }
        expire(now);
        return closeFinished();
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

    /**
     * A member's item was priced out of {@code wanted}. At the cap no wait will make it
     * affordable, so the party posts an expedition for its source; below it, the need joins one
     * already going, so a single haul is for every need of that resource. An item no producer
     * here can make has nobody to send.
     */
    @Override
    public void pricedOutOf(AgentId who, WorkItem item, ObtainItem wanted, double tolerance, BrainContext ctx) {
        Optional<ItemSpec> source = Producers.sourceOf(wanted.spec());
        if (source.isEmpty()) {
            return;
        }
        Optional<Expedition> going = expeditionFor(source.get());
        if (going.isEmpty() && tolerance < WorkToleranceCurve.CAP) {
            return;
        }
        Expedition expedition = sendFor(source.get(), who, item.describe(), wanted.count(), item.priority(),
                wanted.pursued(), ctx.percepts().time());
        ctx.journal().record(Category.PROJECT, item.describe(),
                (going.isEmpty() ? "out of reach — " : "joins the ") + expedition.describe());
    }

    /**
     * A member's item had no way at all to {@code wanted}: the need joins its source's expedition,
     * posted if there is none, and counts once it has gone on failing past
     * {@link Expedition#SEARCH_PATIENCE} — then somebody who knows a way goes, or somebody looks.
     */
    @Override
    public void noWayTo(AgentId who, WorkItem item, ObtainItem wanted, BrainContext ctx) {
        Optional<ItemSpec> source = Producers.sourceOf(wanted.spec());
        if (source.isEmpty()) {
            return;
        }
        boolean fresh = expeditionFor(source.get()).isEmpty();
        long now = ctx.percepts().time();
        Expedition expedition = sendFor(source.get(), new Expedition.Need(who, item.describe(), wanted.count(),
                item.priority(), wanted.pursued(), now, false, now, true), now);
        if (fresh) {
            ctx.journal().record(Category.PROJECT, item.describe(), "no way to it — " + expedition.describe());
        }
    }

    /** The open expedition for {@code source}, if one is going. */
    public Optional<Expedition> expeditionFor(ItemSpec source) {
        for (Project project : projects()) {
            if (project instanceof Expedition far && far.resource() == source && !far.finished()) {
                return Optional.of(far);
            }
        }
        return Optional.empty();
    }

    /** Adds a need to the expedition for {@code source}, posting one if none is going. */
    public Expedition sendFor(ItemSpec source, AgentId who, String what, int count, double priority,
                              java.util.Set<String> pursued, long now) {
        return sendFor(source, new Expedition.Need(who, what, count, priority, java.util.Set.copyOf(pursued), now),
                now);
    }

    /** The same with the need as given — an operator's is {@linkplain Expedition.Need#standing standing}. */
    public Expedition sendFor(ItemSpec source, Expedition.Need need, long now) {
        Optional<Expedition> going = expeditionFor(source);
        Expedition expedition = going.orElseGet(() -> new Expedition(source, party));
        expedition.need(need, now);
        if (going.isEmpty()) {
            post(expedition);
        }
        return expedition;
    }

    // ── continuity ───────────────────────────────────────────────────────────────────────────

    /**
     * One posted project and every hold on it — the store's row shape.
     *
     * <p>Holds travel with the project because a {@link WorkKey} names an item <em>within</em> the
     * project that minted it: two projects clearing overlapping boxes would write the same key.
     */
    public record Row(ProjectState project, List<Hold> holds, int handle, List<Steps> steps) {
        /** A row saved before handles were: numbered afresh on restore. */
        public Row(ProjectState project, List<Hold> holds) {
            this(project, holds, 0);
        }

        public Row(ProjectState project, List<Hold> holds, int handle) {
            this(project, holds, handle, List.of());
        }
    }

    /** Budget an errand has earned by being priced out — see {@link Board#pricedOut}. */
    public record Steps(WorkKey key, int steps) {
    }

    /** Who was holding which item when the world stopped, and until when; 0 is "a fresh TTL". */
    public record Hold(WorkKey key, AgentId who, long until) {
        public Hold(WorkKey key, AgentId who) {
            this(key, who, 0L);
        }
    }

    /**
     * Every party project posted here, with its holds, in post order.
     *
     * <p>A project that is not a {@link PartyProject} is never saved — the same rule {@link #tick}
     * follows for ticking one.
     */
    public List<Row> snapshot(long now) {
        List<Map.Entry<WorkItem, AgentId>> live = held(now);
        List<Row> rows = new ArrayList<>();
        for (Project project : projects()) {
            if (!(project instanceof PartyProject party)) {
                continue;
            }
            List<Hold> holds = new ArrayList<>();
            for (Map.Entry<WorkItem, AgentId> hold : live) {
                party.keyOf(hold.getKey()).ifPresent(key -> holds.add(
                        new Hold(key, hold.getValue(), leaseUntil(hold.getKey()))));
            }
            List<Steps> steps = new ArrayList<>();
            budgetStepsOf(project).forEach((key, n) -> steps.add(new Steps(key, n)));
            rows.add(new Row(party.snapshot(), List.copyOf(holds), handleOf(project).orElse(0), List.copyOf(steps)));
        }
        return List.copyOf(rows);
    }

    /**
     * Posts every saved project back and gives each member their errand back.
     *
     * <p>Through {@link Board#reclaim}, never {@code claim}: re-<em>taking</em> puts an errand
     * through scoring and can hand it to somebody else. Ticks do not pass while a server is down, so
     * a hold was never near expiring. A hold whose item is gone is dropped — and, through
     * {@link PartyProject#holdsRestored}, so is the mirror case: an item whose hold is gone.
     * {@link #snapshot} writes a hold only for a LIVE lease while a project writes every
     * commitment it holds, so a save landing between a lease's death and the next {@link #tick}
     * carries one without the other, and nothing later can sweep a commitment with no lease.
     *
     * @return how many saved projects could not be rebuilt — its {@link ProjectState#type()} names
     *         nothing {@link PartyProjects} has, or (an unknown {@link Felling} id, today) that
     *         type's own {@link ProjectType#restore} refused it — never silently zero. A row whose
     *         type the CODEC never recognised at all never reaches here: that failure is caught
     *         earlier, by {@code StoreGuard}'s row count, and is a different accident from this one.
     */
    public int restore(List<Row> rows, long now) {
        int unknown = 0;
        for (Row row : rows) {
            Optional<? extends PartyProject> rebuilt = PartyProjects.byId(row.project().type())
                    .flatMap(type -> type.restore(row.project(), now));
            if (rebuilt.isEmpty()) {
                unknown++;
                continue;
            }
            PartyProject project = rebuilt.get();
            if (row.handle() > 0) {
                postAt(row.handle(), project);
            } else {
                post(project);
            }
            for (Hold hold : row.holds()) {
                project.itemFor(hold.key()).ifPresent(item -> {
                    if (hold.until() > 0) {
                        reclaimUntil(item, hold.who(), hold.until());
                    } else {
                        reclaim(item, hold.who(), now);
                    }
                    // `reclaim` skips the bidding `claim` does, and that includes telling the
                    // project — which would otherwise think the errand free and withdraw it out
                    // from under the member still walking to it. WITH the holder: a project that
                    // files a claim under its claimant (Gather) hears nothing from the one-arg
                    // form, and the two-arg default delegates back to it for the projects that
                    // only override that one.
                    project.claimed(item, hold.who());
                });
            }
            project.holdsRestored();
            Map<WorkKey, Integer> steps = new java.util.HashMap<>();
            row.steps().forEach(saved -> steps.put(saved.key(), saved.steps()));
            restoreBudgetSteps(project, steps);
        }
        return unknown;
    }
}
