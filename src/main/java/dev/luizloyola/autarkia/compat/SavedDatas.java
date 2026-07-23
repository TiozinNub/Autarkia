package dev.luizloyola.autarkia.compat;

import com.mojang.serialization.Codec;
import java.util.function.Supplier;
import net.minecraft.resources.Identifier;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

/**
 * Builds a {@link SavedDataType} across the version divide: 26.1 keys a type by a namespaced
 * {@link Identifier}, older targets by a plain {@code String}. Callers pass the {@code Identifier};
 * where a String is required this derives a filesystem-safe {@code namespace_path} key.
 */
public final class SavedDatas {
    private SavedDatas() {}

    public static <T extends SavedData> SavedDataType<T> type(
            Identifier id, Supplier<T> factory, Codec<T> codec, DataFixTypes fixType) {
        //? if >=26.1 {
        return new SavedDataType<>(id, factory, codec, fixType);
        //?} else {
        /*return new SavedDataType<>(id.getNamespace() + "_" + id.getPath(), factory, codec, fixType);
        *///?}
    }
}
