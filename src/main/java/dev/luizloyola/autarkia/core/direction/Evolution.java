package dev.luizloyola.autarkia.core.direction;

import dev.luizloyola.autarkia.core.board.PartyBoard;
import dev.luizloyola.autarkia.core.board.PartyProject;
import dev.luizloyola.autarkia.core.board.Project;
import dev.luizloyola.autarkia.core.board.SetUp;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.function.Predicate;

/**
 * One party's beat of layer 4: judge every Direction in force, post or withdraw its work, record
 * what completed, and reach whatever that unlocks. Entity-free, like the board it posts to.
 *
 * <p>Evolution is checkpoints, not time: nothing here reads a clock, and nothing but a completed
 * requirement moves a party on.
 */
public final class Evolution {

    private Evolution() {
    }

    /** What one beat changed — what the host writes to its members' journals. */
    public record Outcome(List<Posted> posted, List<Withdrawn> withdrawn, List<DirectionId> completed,
                          List<String> reached, boolean stalls) {

        public boolean changedProgress() {
            return !completed.isEmpty() || !reached.isEmpty() || stalls;
        }
    }

    public record Posted(Direction direction, PartyProject project, int handle) {
    }

    public record Withdrawn(Direction direction, Project project) {
    }

    public static Outcome beat(Tree tree, PartyProgress progress, PartyView view, PartyBoard board) {
        return beat(tree, progress, view, board, project -> false);
    }

    /** @param worked whether somebody is working a project's errand right now, for {@link #watch} */
    public static Outcome beat(Tree tree, PartyProgress progress, PartyView view, PartyBoard board,
                               Predicate<Project> worked) {
        boolean stalls = false;
        List<Posted> posted = new ArrayList<>();
        List<Withdrawn> withdrawn = new ArrayList<>();
        List<DirectionId> completed = new ArrayList<>();
        for (Direction direction : tree.inForce(progress.reached())) {
            Optional<DirectionLine> line = Lines.byId(direction.line());
            if (line.isEmpty()) {
                continue;
            }
            Status status = line.get().judge(direction, view);
            Optional<Project> work = board.projects().stream()
                    .filter(project -> line.get().isWork(project, direction, view))
                    .findFirst();
            switch (status.reading()) {
                case MET -> {
                    stalls |= progress.stall(direction.id(), null);
                    if (progress.complete(direction.id())) {
                        completed.add(direction.id());
                    }
                    // Met from the world, whoever did it: whatever is still posted toward it is
                    // work nobody needs — a gather an operator's own delivery already covered.
                    if (work.isPresent() && !work.get().finished()) {
                        OptionalInt handle = board.handleOf(work.get());
                        if (handle.isPresent()) {
                            board.cancel(handle.getAsInt());
                            withdrawn.add(new Withdrawn(direction, work.get()));
                        }
                    }
                }
                case UNMET -> {
                    stalls |= watch(line.get(), direction, view, progress, work.filter(worked.negate()).isPresent());
                    if (work.isPresent() && !work.get().finished()
                            && line.get().givesWay(work.get(), direction, view)) {
                        OptionalInt handle = board.handleOf(work.get());
                        if (handle.isPresent()) {
                            board.cancel(handle.getAsInt());
                            withdrawn.add(new Withdrawn(direction, work.get()));
                            work = Optional.empty();
                        }
                    }
                    if (work.isEmpty()) {
                        PartyProject project = line.get().post(direction, view,
                                tree.priorityOf(direction, progress.reached()));
                        // A building going up withdraws a set-up on its site: one posted there went
                        // again each beat, 4,451 times in five minutes (forest, 2026-10-02).
                        if (!(project instanceof SetUp setUp && SetUp.onASite(view.structures(), setUp.near()))) {
                            int handle = board.post(project);
                            posted.add(new Posted(direction, project, handle));
                        }
                    }
                }
                case UNKNOWN, WAITING -> {
                    // Nothing to go on: a store out of sight, or nowhere to put anything yet.
                }
            }
        }
        List<String> reached = tree.unlocked(progress.reached(), progress.checkpoints());
        reached.forEach(progress::reach);
        return new Outcome(posted, withdrawn, completed, reached, stalls);
    }

    /**
     * One more beat of an unmet line's {@link PartyProgress.Stall}: any rise in its measure starts
     * it again, and only a beat with its work posted and nobody on it counts — a cook at the fire
     * is getting somewhere though HOME's food has gone down. Whether that changed what is kept.
     */
    private static boolean watch(DirectionLine line, Direction direction, PartyView view,
                                 PartyProgress progress, boolean idleWork) {
        OptionalInt measure = line.measure(direction, view);
        if (measure.isEmpty()) {
            return false;
        }
        int now = measure.getAsInt();
        PartyProgress.Stall was = progress.stall(direction.id()).orElse(null);
        int beats = was == null || now > was.last() ? 0 : was.beats() + (idleWork ? 1 : 0);
        return progress.stall(direction.id(), new PartyProgress.Stall(now, beats));
    }

    /**
     * Tells each line in force about finished work the board has just closed, and returns the
     * Directions that heard. Called by the host on the tick the board closes it, since a closed
     * project's ledger is gone with it. A line knows its work by content, as the beat does, so an
     * operator's identical project counts as its own.
     *
     * <p>A map of each line's work kept between beats did this until 2026-09-26, and it lived in
     * memory: a project closed between the last beat and a restart was never heard of, so a
     * finished clearing was posted again with an empty grid.
     */
    public static List<DirectionId> collect(Tree tree, PartyProgress progress, PartyView view,
                                            List<Project> closed) {
        List<DirectionId> heard = new ArrayList<>();
        for (Project project : closed) {
            for (Direction direction : tree.inForce(progress.reached())) {
                Optional<DirectionLine> line = Lines.byId(direction.line());
                if (line.isPresent() && line.get().isWork(project, direction, view)) {
                    line.get().finished(project, direction, progress);
                    heard.add(direction.id());
                }
            }
        }
        return heard;
    }

    /**
     * Every project on the board that is some Direction's work under {@code view} — what a move of
     * HOME withdraws, judged against the HOME being left, since the work names its area.
     */
    public static List<Project> ownWork(Tree tree, PartyProgress progress, PartyView view,
                                        PartyBoard board) {
        List<Project> own = new ArrayList<>();
        for (Project project : board.projects()) {
            for (Direction direction : tree.inForce(progress.reached())) {
                Optional<DirectionLine> line = Lines.byId(direction.line());
                if (line.isPresent() && line.get().isWork(project, direction, view)) {
                    own.add(project);
                    break;
                }
            }
        }
        return own;
    }
}
