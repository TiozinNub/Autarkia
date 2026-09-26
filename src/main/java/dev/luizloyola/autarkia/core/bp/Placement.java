package dev.luizloyola.autarkia.core.bp;

import dev.luizloyola.autarkia.core.bp.Blueprint.Facing;

/**
 * Which of a blueprint's eight placements: the direction the drawing's north edge faces in the
 * world, and whether the drawing is mirrored west–east first (decision: Luiz, 2026-09-26). This
 * moves cells only; block states turn in compat, by vanilla's own {@code mirror} then
 * {@code rotate}, in the same order.
 */
public record Placement(Facing north, boolean flip) {

    public static final Placement AS_DRAWN = new Placement(Facing.NORTH, false);

    /** Quarter turns clockwise: the north edge facing east is one. */
    public int turns() {
        return north.ordinal();
    }

    public int width(int width, int depth) {
        return turns() % 2 == 0 ? width : depth;
    }

    public int depth(int width, int depth) {
        return turns() % 2 == 0 ? depth : width;
    }

    /** Where the drawing's cell {@code (x, z)} lands in the placed footprint, as {@code {x, z}}. */
    public int[] cell(int x, int z, int width, int depth) {
        if (flip) {
            x = width - 1 - x;
        }
        for (int i = 0; i < turns(); i++) {
            int turned = depth - 1 - z;
            z = x;
            x = turned;
            int swap = width;
            width = depth;
            depth = swap;
        }
        return new int[] {x, z};
    }

    /** A direction as drawn, as placed. */
    public Facing facing(Facing drawn) {
        Facing out = flip && (drawn == Facing.EAST || drawn == Facing.WEST) ? drawn.opposite() : drawn;
        for (int i = 0; i < turns(); i++) {
            out = out.clockwise();
        }
        return out;
    }

    /**
     * The anchor is the centre of the placed footprint (decision: Luiz, 2026-09-26), rounded north
     * and west when a side is even, so a turn keeps an odd building exactly in place and moves an
     * even one by at most a block.
     */
    public static int offset(int cell, int size) {
        return cell - (size - 1) / 2;
    }
}
