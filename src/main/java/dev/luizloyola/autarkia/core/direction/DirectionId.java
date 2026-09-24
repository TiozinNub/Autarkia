package dev.luizloyola.autarkia.core.direction;

import java.util.Objects;

/**
 * One Direction by the node that pursues it and its line — {@code autarkia:wood} / {@code wood}. What
 * a requirement names and what a checkpoint records.
 */
public record DirectionId(String node, String line) {

    public DirectionId {
        Objects.requireNonNull(node, "node");
        Objects.requireNonNull(line, "line");
    }

    @Override
    public String toString() {
        return node + "/" + line;
    }
}
