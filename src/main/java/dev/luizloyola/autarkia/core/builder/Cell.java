package dev.luizloyola.autarkia.core.builder;

/** A cell of a plan, in the drawing's coordinates. */
public record Cell(int layer, int x, int z) {

    public Cell offset(int dLayer, int dx, int dz) {
        return new Cell(layer + dLayer, x + dx, z + dz);
    }
}
