package dev.luizloyola.autarkia.core.builder;

/**
 * The parts a building goes up in, detected from its plan (builder spec, *Sections*). The order is
 * a priority, not a wall: a gable goes up as the roof under it does, and the proved order says when.
 */
public enum Section {
    FLOOR, WALLS, LIGHTS, CEILING, DOORS, EXTERIOR, INTERIOR;

    /** Outside and inside decor go up together, after the doors. */
    public int rank() {
        return this == INTERIOR ? EXTERIOR.ordinal() : ordinal();
    }
}
