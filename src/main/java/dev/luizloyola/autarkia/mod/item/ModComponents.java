package dev.luizloyola.autarkia.mod.item;

import dev.luizloyola.autarkia.core.person.PersonId;
import java.util.function.UnaryOperator;
import net.minecraft.core.Registry;
import net.minecraft.core.UUIDUtil;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;

/** Registers Autarkia's item data components. Call {@link #init()} from mod init. */
public final class ModComponents {
    private ModComponents() {}

    /**
     * The person a debug wand has selected — the stack's binding to a {@link PersonId}. Both
     * {@code persistent} (in the stack's NBT, so a selection survives save/reload) and
     * {@code networkSynchronized}. Stored as the bare {@link java.util.UUID}.
     */
    public static final DataComponentType<PersonId> SELECTED_PERSON = register("selected_person",
            builder -> builder
                    .persistent(UUIDUtil.CODEC.xmap(PersonId::of, PersonId::value))
                    .networkSynchronized(UUIDUtil.STREAM_CODEC.map(PersonId::of, PersonId::value)));

    private static <T> DataComponentType<T> register(
            String name, UnaryOperator<DataComponentType.Builder<T>> op) {
        Identifier id = Identifier.fromNamespaceAndPath("autarkia", name);
        return Registry.register(BuiltInRegistries.DATA_COMPONENT_TYPE, id,
                op.apply(DataComponentType.<T>builder()).build());
    }

    /** Triggers registration via class load. */
    public static void init() {}
}
