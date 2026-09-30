package dev.luizloyola.autarkia.core.direction;

import dev.luizloyola.anima.core.inv.ItemSpec;
import dev.luizloyola.autarkia.core.board.PartyProject;
import dev.luizloyola.autarkia.core.board.Project;
import java.util.Optional;

/**
 * What a line of Directions means: its condition, and the work it posts while the condition does
 * not hold. <b>It never executes</b> — it posts ordinary projects to its party's board, and a
 * condition is judged from the world, never from which project did the work.
 *
 * <p>Registered in {@link Lines}; a node file names a line by its {@link #id}.
 */
public interface DirectionLine {

    String id();

    /** What this line goes after, for the load check that a node asks only what it opens. */
    Optional<ItemSpec> seeks();

    /** What is wrong with this version's data, or empty — a missing count, say. */
    default Optional<String> check(Direction direction) {
        return Optional.empty();
    }

    Status judge(Direction direction, PartyView party);

    /**
     * Whether this project is this line's work, whoever posted it. Asked of everything on the
     * party's board, so the same work is never posted twice — and an operator's identical project
     * serves as well as the line's own.
     */
    boolean isWork(Project project, Direction direction, PartyView party);

    /**
     * The work to post while the condition does not hold. Every line but {@code home} waits for a
     * HOME before it is unmet.
     */
    PartyProject post(Direction direction, PartyView party, double priority);

    /**
     * A project of this line's work closed having finished. Its ledger is gone with it, so this is
     * the line's one chance to keep what it learned. Nothing by default.
     */
    default void finished(Project project, Direction direction, PartyProgress progress) {
    }
}
