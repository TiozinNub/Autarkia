package dev.luizloyola.autarkia.core.inv;

import java.util.Objects;

/**
 * A stack of one item kind, as pure data — the {@code core} layer's own item value type, carrying
 * no {@code net.minecraft} knowledge; {@code compat} translates to and from the real thing.
 * Immutable, and {@code count == 0} (or a blank {@code id}) is {@link #EMPTY}.
 *
 * <p><b>Components</b> (durability, enchantments, custom name/data, …) are an <em>opaque</em>
 * string {@code core} only stores, compares and persists, so the item round-trips losslessly;
 * {@code compat} owns its meaning (the SNBT of the item's data-component patch). Two stacks merge
 * only when both {@code id} and {@code components} match.
 *
 * @param id           namespaced item id, e.g. {@code "minecraft:oak_log"}; identifies the kind
 * @param count        how many, {@code >= 0}; {@code 0} means empty (see {@link #EMPTY})
 * @param maxStackSize the per-stack cap for this kind (supplied by {@code compat} from the item),
 *                     {@code >= 1}
 * @param components   opaque component payload (compat's SNBT); {@code ""} for the plain item
 */
public record ItemStack(String id, int count, int maxStackSize, String components) {
    /** The absence of an item: a blank kind, zero count, no components; stacks with nothing. */
    public static final ItemStack EMPTY = new ItemStack("", 0, 1, "");

    public ItemStack {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(components, "components");
        if (count < 0) throw new IllegalArgumentException("count must be >= 0: " + count);
        if (maxStackSize < 1) throw new IllegalArgumentException("maxStackSize must be >= 1: " + maxStackSize);
    }

    /** A plain (component-less) stack of {@code count} of item {@code id}, capped at {@code maxStackSize}. */
    public static ItemStack of(String id, int count, int maxStackSize) {
        return new ItemStack(id, count, maxStackSize, "");
    }

    /** A stack carrying an opaque {@code components} payload (see the class doc). */
    public static ItemStack of(String id, int count, int maxStackSize, String components) {
        return new ItemStack(id, count, maxStackSize, components);
    }

    public boolean isEmpty() {
        return id.isEmpty() || count == 0;
    }

    /** This same item (kind, cap, components) with a different count; {@code <= 0} collapses to {@link #EMPTY}. */
    public ItemStack withCount(int newCount) {
        if (newCount <= 0 || id.isEmpty()) return EMPTY;
        return new ItemStack(id, newCount, maxStackSize, components);
    }

    /**
     * Whether {@code other} can merge into this stack: both non-empty, the same kind, <em>and</em>
     * the same components. Available headroom is the caller's concern (see {@link #remainingSpace()}).
     */
    public boolean canStackWith(ItemStack other) {
        return !isEmpty() && !other.isEmpty() && id.equals(other.id) && components.equals(other.components);
    }

    /** Room left before hitting {@link #maxStackSize()}; {@code 0} for an empty stack. */
    public int remainingSpace() {
        return isEmpty() ? 0 : Math.max(0, maxStackSize - count);
    }
}
