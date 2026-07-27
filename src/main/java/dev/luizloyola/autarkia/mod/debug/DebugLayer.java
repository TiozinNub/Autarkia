package dev.luizloyola.autarkia.mod.debug;

import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * The independently switchable halves of the in-world debug view ({@link DebugView}), drawn over the
 * selected Person as gizmo lines, boxes and floating text. No layer implies another: chasing a
 * pathfinding bug with the peer lines on is noise.
 *
 * <p>Declaration order is load-bearing twice — it is the debug wand's cycle order ({@link #next})
 * and the wire bit order ({@link #bit}), so a client and server that disagree about it disagree
 * about everything. Add a layer at the END.
 */
public enum DebugLayer {
    /** Waypoint polyline coloured by move type, the current leg highlighted, and the goal cell. */
    PATH,
    /** The arbiter's pressures and the running task, stacked as floating text over their head. */
    BRAIN,
    /** Remembered points of interest: an anchor marker and the bounds box they believe in. */
    MEMORY,
    /** The view cone, and a line to everyone currently perceived, coloured by channel. */
    PEERS;

    /** The command literal and config-facing name — lower case, no underscores in v1. */
    public String key() {
        return name().toLowerCase(Locale.ROOT);
    }

    /** This layer's bit in the wire mask. */
    public int bit() {
        return 1 << ordinal();
    }

    public boolean in(int mask) {
        return (mask & bit()) != 0;
    }

    /** Packs a layer set into the wire mask. */
    public static int mask(Set<DebugLayer> layers) {
        int mask = 0;
        for (DebugLayer layer : layers) {
            mask |= layer.bit();
        }
        return mask;
    }

    /** The layer with this command literal, or empty when the token names none. */
    public static Optional<DebugLayer> byKey(String key) {
        for (DebugLayer layer : values()) {
            if (layer.key().equalsIgnoreCase(key)) {
                return Optional.of(layer);
            }
        }
        return Optional.empty();
    }

    /**
     * The debug wand's one-at-a-time cycle: {@code null} (off) starts at the first layer, each
     * advances to the next, and the last falls back off. Empty means "show nothing".
     */
    public static Optional<DebugLayer> next(@Nullable DebugLayer current) {
        if (current == null) {
            return Optional.of(values()[0]);
        }
        int next = current.ordinal() + 1;
        return next < values().length ? Optional.of(values()[next]) : Optional.empty();
    }
}
