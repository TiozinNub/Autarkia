package dev.luizloyola.autarkia.core.bp;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.SortedMap;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;
import org.jspecify.annotations.Nullable;

/**
 * Materials, their forms, and the blocks a blueprint may name. A leaf material maps each form to
 * one block, so a lookup can never answer two (decision: Luiz, 2026-09-25 — single-word forms); a
 * composite material lists other materials.
 *
 * <p>Built from the live registries by {@code compat/}, or read from an exported file by the
 * offline checker. Either way this class is only data and lookups.
 */
public final class Dictionary {

    public static final Dictionary EMPTY = new Dictionary(Map.of(), Map.of());

    /**
     * The block tags core asks about, each block carrying the ones it is in: vanilla's where vanilla
     * has one, {@code autarkia:furnishing} for what stands in a room without being part of it — a
     * crafting table, a chest, a furnace.
     */
    public static final List<String> TAGS = List.of("minecraft:doors", "minecraft:trapdoors",
            "minecraft:fence_gates", "minecraft:climbable", "minecraft:wall_hanging_signs",
            "minecraft:ceiling_hanging_signs", "autarkia:furnishing");

    /**
     * What a check needs to know about a block, read from its default state.
     *
     * @param properties every property and its values, in the block's order
     * @param solid      a full cube you cannot walk through — a wall, not a door or a torch
     * @param obstructs  stands half a block tall or more, so nobody walks through it and the outside air
     *                   does not reach past it — stairs, a fence, a slab, a bed; not a carpet, a torch, or
     *                   a ladder, which is climbed
     * @param falls      sand, gravel: needs a block under it
     * @param item       the item that places it, empty when nothing does
     * @param tags       which of {@link #TAGS} it is in
     */
    public record BlockInfo(String id, Map<String, List<String>> properties, Map<String, String> defaults,
                            boolean solid, boolean obstructs, int light, boolean falls, String item,
                            Set<String> tags) {
        public BlockInfo {
            Objects.requireNonNull(id, "id");
            properties = Collections.unmodifiableSortedMap(new TreeMap<>(properties));
            defaults = Map.copyOf(defaults);
            tags = Collections.unmodifiableSortedSet(new TreeSet<>(tags));
        }

        public boolean is(String tag) {
            return tags.contains(tag);
        }

        public boolean has(String property, String value) {
            List<String> values = properties.get(property);
            return values != null && values.contains(value);
        }
    }

    /** A leaf ({@code forms} non-empty) or a composite ({@code members} non-empty). */
    public record Material(String id, Map<String, String> forms, List<String> members) {
        public Material {
            Objects.requireNonNull(id, "id");
            forms = Collections.unmodifiableSortedMap(new TreeMap<>(forms));
            members = List.copyOf(members);
        }

        public boolean isComposite() {
            return !members.isEmpty();
        }
    }

    private final SortedMap<String, BlockInfo> blocks;
    private final SortedMap<String, Material> materials;
    private final SortedSet<String> forms;
    private volatile @Nullable Map<String, String> wallTwins;

    public Dictionary(Map<String, BlockInfo> blocks, Map<String, Material> materials) {
        this.blocks = Collections.unmodifiableSortedMap(new TreeMap<>(blocks));
        this.materials = Collections.unmodifiableSortedMap(new TreeMap<>(materials));
        SortedSet<String> all = new TreeSet<>();
        for (Material material : materials.values()) {
            all.addAll(material.forms().keySet());
        }
        this.forms = Collections.unmodifiableSortedSet(all);
    }

    public Optional<BlockInfo> block(String id) {
        return Optional.ofNullable(blocks.get(id));
    }

    public SortedMap<String, BlockInfo> blocks() {
        return blocks;
    }

    public Optional<Material> material(String id) {
        return Optional.ofNullable(materials.get(id));
    }

    public SortedMap<String, Material> materials() {
        return materials;
    }

    public SortedSet<String> forms() {
        return forms;
    }

    public boolean isEmpty() {
        return blocks.isEmpty();
    }

    /** The one block a leaf material lists as {@code form}. Empty for a composite or a missing form. */
    public Optional<String> lookup(String material, String form) {
        Material found = materials.get(material);
        return found == null ? Optional.empty() : Optional.ofNullable(found.forms().get(form));
    }

    /**
     * A standing block to its wall-hung twin — {@code torch} to {@code wall_torch}, a sign to its
     * wall sign — found as the two blocks one item places. Hanging signs are left out: theirs
     * hangs from above, and the wall one is a bracket, not the same thing turned.
     */
    public Map<String, String> wallTwins() {
        Map<String, String> twins = wallTwins;
        if (twins == null) {
            twins = findWallTwins();
            wallTwins = twins;
        }
        return twins;
    }

    private Map<String, String> findWallTwins() {
        Map<String, List<BlockInfo>> byItem = new HashMap<>();
        for (BlockInfo info : blocks.values()) {
            if (!info.item().isEmpty()) {
                byItem.computeIfAbsent(info.item(), item -> new ArrayList<>()).add(info);
            }
        }
        Map<String, String> twins = new HashMap<>();
        for (List<BlockInfo> placed : byItem.values()) {
            if (placed.size() != 2) {
                continue;
            }
            for (int k = 0; k < 2; k++) {
                BlockInfo standing = placed.get(k);
                BlockInfo wall = placed.get(1 - k);
                List<String> facings = wall.properties().get("facing");
                if (Ids.path(wall.id()).contains("wall") && facings != null && facings.size() == 4
                        && !standing.properties().containsKey("facing")
                        && !standing.properties().containsKey("attached")) {
                    twins.put(standing.id(), wall.id());
                }
            }
        }
        return Collections.unmodifiableMap(twins);
    }

    /** Every leaf a material stands for — itself when it is one. A cycle is walked once. */
    public Set<String> leaves(String material) {
        Set<String> leaves = new LinkedHashSet<>();
        Set<String> seen = new HashSet<>();
        Deque<String> todo = new ArrayDeque<>();
        todo.add(material);
        while (!todo.isEmpty()) {
            String next = todo.poll();
            if (!seen.add(next)) {
                continue;
            }
            Material found = materials.get(next);
            if (found == null) {
                continue;
            }
            if (found.isComposite()) {
                todo.addAll(found.members());
            } else {
                leaves.add(next);
            }
        }
        return leaves;
    }
}
