package dev.luizloyola.autarkia.mod.direction;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.luizloyola.autarkia.compat.inv.ItemIds;
import dev.luizloyola.autarkia.core.direction.Direction;
import dev.luizloyola.autarkia.core.direction.DirectionId;
import dev.luizloyola.autarkia.core.direction.Node;
import dev.luizloyola.autarkia.core.direction.NodeKind;
import dev.luizloyola.autarkia.core.direction.Requirements;
import java.io.Reader;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;

/**
 * The tree as datapack files: one per node, at {@code data/<namespace>/autarkia/node/<path>.json},
 * named {@code <namespace>:<path>}.
 *
 * <pre>{@code
 * {
 *   "note": "free text, ignored",
 *   "kind": "core",                        // or "side"
 *   "parents": ["autarkia:wood"],
 *   "needs": "all",                        // or "any"; default all
 *   "requires": {"key": "autarkia:wood/wood", "pool": ["autarkia:wood/area"], "of": 1},
 *   "directions": [{"line": "wood", "count": 256, "priority": 0.5}],
 *   "items": ["#autarkia:node/stone", "minecraft:smoker"],
 *   "acts": []
 * }
 * }</pre>
 *
 * <p>Read in two steps because the two halves are ready at different times: the files during the
 * reload, and the item tags only once the reload has bound them.
 */
public final class NodeFiles {

    /** Where node files live inside a namespace. */
    public static final String DIRECTORY = "autarkia/node";

    private static final Set<String> FIELDS = Set.of(
            "note", "kind", "parents", "needs", "requires", "directions", "items", "acts");

    private static final Set<String> DIRECTION_FIELDS = Set.of("line", "count", "priority");

    private static final Set<String> REQUIRES_FIELDS = Set.of("key", "pool", "of");

    private NodeFiles() {
    }

    /** One node file as written — its items still names and tags, not ids. */
    public record NodeFile(String id, NodeKind kind, List<String> parents, boolean needsAll,
                           Requirements requirements, List<Direction> directions,
                           List<String> items, List<String> acts) {
    }

    /** Every file read, and every one that could not be. */
    public record Read(List<NodeFile> files, List<String> errors) {
        public static final Read NONE = new Read(List.of(), List.of());
    }

    public static Read read(ResourceManager manager) {
        List<NodeFile> files = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        Map<Identifier, Resource> found = manager.listResources(DIRECTORY,
                path -> path.getPath().endsWith(".json"));
        for (Map.Entry<Identifier, Resource> entry : found.entrySet()) {
            String path = entry.getKey().getPath();
            String id = entry.getKey().getNamespace() + ":"
                    + path.substring(DIRECTORY.length() + 1, path.length() - ".json".length());
            try (Reader reader = entry.getValue().openAsReader()) {
                files.add(parse(id, JsonParser.parseReader(reader)));
            } catch (Exception e) {
                errors.add(id + ": " + e.getMessage());
            }
        }
        return new Read(List.copyOf(files), List.copyOf(errors));
    }

    /** One file's JSON as a node. Strict: an unknown field is a typo until proven otherwise. */
    static NodeFile parse(String id, JsonElement json) {
        if (!json.isJsonObject()) {
            throw new IllegalArgumentException("a node file is an object");
        }
        JsonObject object = json.getAsJsonObject();
        onlyFields(object, FIELDS);
        NodeKind kind = switch (string(object, "kind", "core")) {
            case "core" -> NodeKind.CORE;
            case "side" -> NodeKind.SIDE;
            default -> throw new IllegalArgumentException("kind is core or side");
        };
        boolean needsAll = switch (string(object, "needs", "all")) {
            case "all" -> true;
            case "any" -> false;
            default -> throw new IllegalArgumentException("needs is all or any");
        };
        List<Direction> directions = new ArrayList<>();
        for (JsonElement element : array(object, "directions")) {
            JsonObject direction = element.getAsJsonObject();
            onlyFields(direction, DIRECTION_FIELDS);
            if (!direction.has("line")) {
                throw new IllegalArgumentException("a direction names its line");
            }
            String line = direction.get("line").getAsString();
            int count = direction.has("count") ? direction.get("count").getAsInt() : 0;
            Double priority = direction.has("priority") ? direction.get("priority").getAsDouble() : null;
            directions.add(new Direction(new DirectionId(id, line), count, priority));
        }
        return new NodeFile(id, kind, strings(object, "parents"), needsAll, requirements(object),
                directions, strings(object, "items"), strings(object, "acts"));
    }

    private static Requirements requirements(JsonObject object) {
        if (!object.has("requires")) {
            return Requirements.NONE;
        }
        JsonObject requires = object.getAsJsonObject("requires");
        onlyFields(requires, REQUIRES_FIELDS);
        DirectionId key = requires.has("key") ? ref(requires.get("key").getAsString()) : null;
        List<DirectionId> pool = new ArrayList<>();
        for (String ref : strings(requires, "pool")) {
            pool.add(ref(ref));
        }
        int of = requires.has("of") ? requires.get("of").getAsInt() : pool.size();
        return new Requirements(key, pool, of);
    }

    /** {@code autarkia:wood/area} — a node id, then its line after the last slash. */
    private static DirectionId ref(String written) {
        int slash = written.lastIndexOf('/');
        if (slash <= 0 || slash == written.length() - 1) {
            throw new IllegalArgumentException("\"" + written + "\" is not <node>/<line>");
        }
        return new DirectionId(written.substring(0, slash), written.substring(slash + 1));
    }

    /**
     * A file with its items resolved against the registry and its bound tags. An item or tag that
     * is not there is a warning rather than a refusal: a modpack's table may name a mod it does not
     * ship.
     */
    public static Node resolve(NodeFile file, List<String> warnings) {
        Set<String> items = new LinkedHashSet<>();
        for (String entry : file.items()) {
            if (entry.startsWith("#")) {
                Optional<Set<String>> tag = ItemIds.inTag(entry.substring(1));
                if (tag.isEmpty()) {
                    warnings.add(file.id() + ": item tag " + entry + " is not bound");
                } else {
                    items.addAll(tag.get());
                }
            } else if (ItemIds.exists(entry)) {
                items.add(entry);
            } else {
                warnings.add(file.id() + ": item " + entry + " is not registered");
            }
        }
        return new Node(file.id(), file.kind(), file.parents(), file.needsAll(), file.requirements(),
                file.directions(), items, Set.copyOf(file.acts()));
    }

    private static void onlyFields(JsonObject object, Set<String> allowed) {
        for (String field : object.keySet()) {
            if (!allowed.contains(field)) {
                throw new IllegalArgumentException("unknown field \"" + field + "\"");
            }
        }
    }

    private static String string(JsonObject object, String field, String fallback) {
        return object.has(field) ? object.get(field).getAsString().toLowerCase(Locale.ROOT) : fallback;
    }

    private static JsonArray array(JsonObject object, String field) {
        return object.has(field) ? object.getAsJsonArray(field) : new JsonArray();
    }

    private static List<String> strings(JsonObject object, String field) {
        List<String> out = new ArrayList<>();
        for (JsonElement element : array(object, field)) {
            out.add(element.getAsString());
        }
        return out;
    }
}
