package dev.luizloyola.autarkia.core.tree;

import dev.luizloyola.anima.core.brain.sense.Pos;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Where one tree ends and its neighbour begins, as touching cell pairs — the split's boundary
 * made explicit. A seam is a cell of one tree face-to-face with a cell of ANOTHER; contact
 * with ground, air or the tree's own cells is not a seam.
 *
 * <p>Face adjacency only: a shared face is a surface that can be drawn and stared
 * at. A purely diagonal seam has none to show — rare in worldgen, where fused canopies always
 * meet flush somewhere.
 */
public final class TreeSeams {
    private TreeSeams() {
    }

    /**
     * One face-adjacent cross-tree pair: {@code tree} owns {@code cell}, {@code otherTree} owns
     * {@code other}, both indices into the split's trunk list. {@code other} is always one step
     * from {@code cell} along +x, +y or +z, so each touching pair appears once.
     */
    public record Contact(Pos cell, int tree, Pos other, int otherTree) {}

    /** Every cross-tree face contact inside one mass's split. */
    public static List<Contact> contacts(List<TreeShape.Trunk> trees) {
        Map<Pos, Integer> owner = new LinkedHashMap<>();
        for (int i = 0; i < trees.size(); i++) {
            TreeShape.Trunk tree = trees.get(i);
            for (Pos cell : tree.base()) {
                owner.put(cell, i);
            }
            for (Pos cell : tree.column()) {
                owner.put(cell, i);
            }
            for (Pos cell : tree.branches()) {
                owner.put(cell, i);
            }
            for (Pos cell : tree.leaves()) {
                owner.put(cell, i);
            }
        }
        List<Contact> contacts = new ArrayList<>();
        // Positive steps only: each unordered pair is visited from exactly one of its two
        // cells, so no face is ever reported twice regardless of which tree owns which side.
        for (Map.Entry<Pos, Integer> entry : owner.entrySet()) {
            Pos cell = entry.getKey();
            check(owner, contacts, cell, new Pos(cell.x() + 1, cell.y(), cell.z()), entry.getValue());
            check(owner, contacts, cell, new Pos(cell.x(), cell.y() + 1, cell.z()), entry.getValue());
            check(owner, contacts, cell, new Pos(cell.x(), cell.y(), cell.z() + 1), entry.getValue());
        }
        return contacts;
    }

    private static void check(Map<Pos, Integer> owner, List<Contact> contacts,
                              Pos cell, Pos neighbour, int mine) {
        Integer theirs = owner.get(neighbour);
        if (theirs != null && theirs != mine) {
            contacts.add(new Contact(cell, mine, neighbour, theirs));
        }
    }
}
