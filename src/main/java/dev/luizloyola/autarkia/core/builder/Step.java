package dev.luizloyola.autarkia.core.builder;

import dev.luizloyola.autarkia.core.bp.Blueprint.Outcome;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * One placement: a block, or a fixture's two halves, which one item places together — a door, a
 * bed.
 *
 * @param cells  what it fills, the cell placed against first: a door's lower half, a bed's foot
 * @param states the block in each of {@code cells}
 * @param holder what it hangs from or stands on, when it needs one to stay: a wall torch's wall, a
 *               door's floor
 */
public record Step(Section section, List<Cell> cells, List<Outcome> states, @Nullable Cell holder) {

    public Step {
        cells = List.copyOf(cells);
        states = List.copyOf(states);
    }

    public Cell cell() {
        return cells.get(0);
    }

    public Outcome state() {
        return states.get(0);
    }
}
