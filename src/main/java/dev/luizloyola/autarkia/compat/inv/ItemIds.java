package dev.luizloyola.autarkia.compat.inv;

import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;
import net.minecraft.core.HolderSet;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;

/** The item registry as plain ids, for data that names items the way a datapack does. */
public final class ItemIds {

    private ItemIds() {
    }

    /** Every registered item id. */
    public static Set<String> all() {
        Set<String> ids = new LinkedHashSet<>();
        for (Identifier id : BuiltInRegistries.ITEM.keySet()) {
            ids.add(id.toString());
        }
        return ids;
    }

    public static boolean exists(String id) {
        Identifier key = Identifier.tryParse(id);
        return key != null && BuiltInRegistries.ITEM.containsKey(key);
    }

    /**
     * The ids in an item tag, or empty when no such tag is bound. Only true once the server's tags
     * are — at {@code SERVER_STARTED} and after a reload, never inside the reload itself.
     */
    public static Optional<Set<String>> inTag(String tagId) {
        Identifier key = Identifier.tryParse(tagId);
        if (key == null) {
            return Optional.empty();
        }
        Optional<HolderSet.Named<Item>> tag = BuiltInRegistries.ITEM.get(TagKey.create(Registries.ITEM, key));
        if (tag.isEmpty()) {
            return Optional.empty();
        }
        Set<String> ids = new LinkedHashSet<>();
        tag.get().forEach(item -> ids.add(BuiltInRegistries.ITEM.getKey(item.value()).toString()));
        return Optional.of(ids);
    }
}
