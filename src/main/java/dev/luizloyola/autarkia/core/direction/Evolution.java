package dev.luizloyola.autarkia.core.direction;

import dev.luizloyola.autarkia.core.board.PartyBoard;
import dev.luizloyola.autarkia.core.board.PartyProject;
import dev.luizloyola.autarkia.core.board.Project;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;

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
                          List<String> reached) {

        public boolean changedProgress() {
            return !completed.isEmpty() || !reached.isEmpty();
        }
    }

    public record Posted(Direction direction, PartyProject project, int handle) {
    }

    public record Withdrawn(Direction direction, Project project) {
    }

    public static Outcome beat(Tree tree, PartyProgress progress, PartyView view, PartyBoard board) {
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
                    if (work.isEmpty()) {
                        PartyProject project = line.get().post(direction, view,
                                tree.priorityOf(direction, progress.reached()));
                        int handle = board.post(project);
                        posted.add(new Posted(direction, project, handle));
                    }
                }
                case UNKNOWN, WAITING -> {
                    // Nothing to go on: a store out of sight, or nowhere to put anything yet.
                }
            }
        }
        List<String> reached = tree.unlocked(progress.reached(), progress.checkpoints());
        reached.forEach(progress::reach);
        return new Outcome(posted, withdrawn, completed, reached);
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
     * HOME withdraws, judged against the HOME being left, since the work names its plot and yard.
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
