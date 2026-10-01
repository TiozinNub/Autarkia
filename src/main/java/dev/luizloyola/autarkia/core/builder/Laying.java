package dev.luizloyola.autarkia.core.builder;

import dev.luizloyola.anima.core.brain.act.Placing;
import dev.luizloyola.anima.core.brain.sense.Pos;
import java.util.List;

/**
 * A {@link Step} in the world, as a body places it: the block turned as the building faces, the
 * stand the proved order placed it from, and the items it takes.
 *
 * @param also  the step's other cells — a door's upper half, a bed's head
 * @param count items placed: two for a double slab, the candles in a cell
 */
public record Laying(Section section, Placing placing, List<Pos> also, Pos stand, int count) {

    public Laying {
        also = List.copyOf(also);
    }

    public Pos cell() {
        return placing.cell();
    }

    /** What the cell holds once the step is done. */
    public String block() {
        return placing.block().isEmpty() ? placing.itemId() : placing.block();
    }
}
