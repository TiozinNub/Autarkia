package dev.luizloyola.autarkia.core.brain.knowledge;

import dev.luizloyola.autarkia.core.brain.sense.Pos;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The transient tier of the three-tier storage: every block of every structure this person has
 * investigated, so a probed column dismisses in O(1) instead of re-growing the same grove every
 * crossing. Positive claims point at their region's anchor (match refreshes the memory, mismatch
 * invalidates it); negative claims ({@code anchor == null}) mean "investigated, wasn't a thing".
 *
 * <p>RAM-only and hard-bounded: at {@link #MAX_CLAIMS} the oldest fall off FIFO, which is also how
 * negatives age out. A refresh against an evicted anchor misses, and the next mismatch
 * sweeps the leftovers. The per-column index answers what the heightmap cannot: a claim ABOVE the
 * current surface means its block is gone.
 */
public final class ClaimIndex {
    /** Claim capacity. A tuning knob; ~8–30 regions' worth of blocks. */
    public static final int MAX_CLAIMS = 4096;

    /** One claimed cell: which kind of region, its anchor (null = negative), what stood here. */
    public record Claim(PoiKind kind, Pos anchor, BlockKind expected) {
    }

    private final LinkedHashMap<Pos, Claim> byPos = new LinkedHashMap<>();
    private final Map<Column, List<Pos>> byColumn = new HashMap<>();

    /** The claim at this cell, or null. */
    public Claim get(Pos p) {
        return byPos.get(p);
    }

    /** The highest claimed cell in this column, or null when the column holds no claims. */
    public Pos highestIn(Column column) {
        List<Pos> cells = byColumn.get(column);
        if (cells == null) {
            return null;
        }
        Pos highest = null;
        for (Pos p : cells) {
            if (highest == null || p.y() > highest.y()) {
                highest = p;
            }
        }
        return highest;
    }

    /** Claims an accepted region's blocks, pointing them at its anchor. */
    public void claimRegion(PoiKind kind, Pos anchor, Map<Pos, BlockKind> blocks) {
        for (Map.Entry<Pos, BlockKind> entry : blocks.entrySet()) {
            put(entry.getKey(), new Claim(kind, anchor, entry.getValue()));
        }
    }

    /** Claims a rejected growth's blocks as "investigated, wasn't a thing". */
    public void claimNegative(PoiKind kind, Map<Pos, BlockKind> blocks) {
        for (Map.Entry<Pos, BlockKind> entry : blocks.entrySet()) {
            put(entry.getKey(), new Claim(kind, null, entry.getValue()));
        }
    }

    /** Drops one cell's claim (how a changed negative claim clears). */
    public void remove(Pos p) {
        if (byPos.remove(p) != null) {
            unindex(p);
        }
    }

    /** Drops every claim pointing at this region's anchor (the region was invalidated). */
    public void dropRegion(PoiKind kind, Pos anchor) {
        Iterator<Map.Entry<Pos, Claim>> it = byPos.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<Pos, Claim> entry = it.next();
            if (entry.getValue().kind() == kind && anchor.equals(entry.getValue().anchor())) {
                it.remove();
                unindex(entry.getKey());
            }
        }
    }

    public int size() {
        return byPos.size();
    }

    private void put(Pos p, Claim claim) {
        if (byPos.remove(p) != null) {
            unindex(p);
        }
        while (byPos.size() >= MAX_CLAIMS) {
            Pos eldest = byPos.keySet().iterator().next();
            byPos.remove(eldest);
            unindex(eldest);
        }
        byPos.put(p, claim);
        byColumn.computeIfAbsent(new Column(p.x(), p.z()), c -> new ArrayList<>(4)).add(p);
    }

    private void unindex(Pos p) {
        Column column = new Column(p.x(), p.z());
        List<Pos> cells = byColumn.get(column);
        if (cells != null) {
            cells.remove(p);
            if (cells.isEmpty()) {
                byColumn.remove(column);
            }
        }
    }
}
