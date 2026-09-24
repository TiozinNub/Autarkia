package dev.luizloyola.autarkia.core.direction;

import dev.luizloyola.autarkia.core.board.PartyBoard;
import dev.luizloyola.autarkia.core.board.PartyProject;
import dev.luizloyola.autarkia.core.board.Project;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
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

    /**
     * @param tracked each Direction's work as last seen on the board, kept by the caller between
     *                beats: a project that finishes is closed by the board's own tick, and this is
     *                how the line still hears it finished
     */
    public static Outcome beat(Tree tree, PartyProgress progress, PartyView view, PartyBoard board,
                               Map<DirectionId, Project> tracked) {
        List<Posted> posted = new ArrayList<>();
        List<Withdrawn> withdrawn = new ArrayList<>();
        List<DirectionId> completed = new ArrayList<>();
        noteClosed(tree, progress, board, tracked);
        for (Direction direction : tree.inForce(progress.reached())) {
            Optional<DirectionLine> line = Lines.byId(direction.line());
            if (line.isEmpty()) {
                continue;
            }
            Status status = line.get().judge(direction, view);
            Optional<Project> work = board.projects().stream()
                    .filter(project -> line.get().isWork(project, direction, view))
                    .findFirst();
            work.ifPresent(project -> tracked.put(direction.id(), project));
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
                            tracked.remove(direction.id());
                            withdrawn.add(new Withdrawn(direction, work.get()));
                        }
                    }
                }
                case UNMET -> {
                    if (work.isEmpty()) {
                        PartyProject project = line.get().post(direction, view,
                                tree.priorityOf(direction, progress.reached()));
                        int handle = board.post(project);
                        tracked.put(direction.id(), project);
                        posted.add(new Posted(direction, project, handle));
                    }
                }
                case UNKNOWN, NO_HOME -> {
                    // Nothing to go on: a store out of sight, or nowhere to put anything. Waits.
                }
            }
        }
        List<String> reached = tree.unlocked(progress.reached(), progress.checkpoints());
        reached.forEach(progress::reach);
        return new Outcome(posted, withdrawn, completed, reached);
    }

    /** Tells each line about its work that left the board since the last beat, finished. */
    private static void noteClosed(Tree tree, PartyProgress progress, PartyBoard board,
                                   Map<DirectionId, Project> tracked) {
        Iterator<Map.Entry<DirectionId, Project>> it = tracked.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<DirectionId, Project> entry = it.next();
            if (board.handleOf(entry.getValue()).isPresent()) {
                continue;
            }
            it.remove();
            if (!entry.getValue().finished()) {
                continue; // cancelled rather than done: posted again next beat, if still wanted
            }
            DirectionId id = entry.getKey();
            tree.node(id.node()).flatMap(node -> node.directions().stream()
                            .filter(direction -> direction.id().equals(id)).findFirst())
                    .ifPresent(direction -> Lines.byId(id.line()).ifPresent(line ->
                            line.finished(entry.getValue(), direction, progress)));
        }
    }
}
