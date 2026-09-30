package dev.luizloyola.autarkia.compat.home;

import dev.luizloyola.anima.core.terrain.Landscape;
import dev.luizloyola.autarkia.core.direction.HomeJudge.Keep;
import dev.luizloyola.autarkia.core.direction.HomeJudge.Refusal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.IntBinaryOperator;
import java.util.function.ToIntFunction;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.StructureTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureStart;

/**
 * What the server itself records about a stretch of the world, for the refusals a home search
 * makes: the structures placed there, and the biomes. "Small omniscience but fine for this purpose"
 * (docs/superpowers/specs/2026-09-25-home-search-design.md, decision 14). Never loads a chunk.
 */
public final class HomeRecords {

    /** Outposts, witch huts, mansions, monuments; a modpack adds its own. */
    public static final TagKey<Structure> MONSTERS = TagKey.create(Registries.STRUCTURE,
            Identifier.fromNamespaceAndPath("autarkia", "home_monsters"));

    /** Temples and igloos. */
    public static final TagKey<Structure> TEMPLES = TagKey.create(Registries.STRUCTURE,
            Identifier.fromNamespaceAndPath("autarkia", "home_temples"));

    /** Biomes no plot may touch: mushroom fields. */
    public static final TagKey<Biome> REFUSED_BIOMES = TagKey.create(Registries.BIOME,
            Identifier.fromNamespaceAndPath("autarkia", "home_refused"));

    /**
     * The structures whose records start in the loaded chunks of a box, sorted: villages as their
     * bounding boxes, the rest as boxes to keep a home away from.
     */
    public record Structures(List<BoundingBox> villages, List<Keep> keeps) {
    }

    private HomeRecords() {
    }

    /**
     * The structures starting in loaded chunks within {@code [minX, maxX] × [minZ, maxZ]}.
     * {@code distance} gives the keep-away for each refusal. A structure whose start lies outside
     * the box is not seen, so a caller widens the box by the largest structure it cares about.
     */
    public static Structures structures(ServerLevel level, int minX, int minZ, int maxX, int maxZ,
                                        ToIntFunction<Refusal> distance) {
        Registry<Structure> registry = level.registryAccess().lookupOrThrow(Registries.STRUCTURE);
        List<BoundingBox> villages = new ArrayList<>();
        List<Keep> keeps = new ArrayList<>();
        for (int chunkX = minX >> 4; chunkX <= maxX >> 4; chunkX++) {
            for (int chunkZ = minZ >> 4; chunkZ <= maxZ >> 4; chunkZ++) {
                ChunkAccess chunk = level.getChunk(chunkX, chunkZ, ChunkStatus.FULL, false);
                if (chunk == null) {
                    continue;
                }
                for (StructureStart start : chunk.getAllStarts().values()) {
                    if (!start.isValid()) {
                        continue;
                    }
                    Holder<Structure> holder = registry.wrapAsHolder(start.getStructure());
                    BoundingBox box = start.getBoundingBox();
                    if (holder.is(StructureTags.VILLAGE)) {
                        villages.add(box);
                        continue;
                    }
                    Refusal why = holder.is(MONSTERS) ? Refusal.MONSTERS
                            : holder.is(TEMPLES) ? Refusal.TEMPLE
                            : holder.is(StructureTags.RUINED_PORTAL) ? Refusal.PORTAL
                            : null;
                    if (why != null) {
                        keeps.add(new Keep(why, box.minX(), box.minZ(), box.maxX(), box.maxZ(),
                                distance.applyAsInt(why)));
                    }
                }
            }
        }
        return new Structures(villages, keeps);
    }

    /**
     * Whether a column lies in a refused biome, read at its ground ({@code groundY}) and asked
     * once per 4×4 cell, the biome's own grain.
     */
    public static Landscape.Columns refusedBiome(ServerLevel level, IntBinaryOperator groundY) {
        Map<Long, Boolean> cells = new HashMap<>();
        return (x, z) -> cells.computeIfAbsent(((long) (x >> 2) << 32) ^ ((z >> 2) & 0xFFFFFFFFL),
                cell -> level.getNoiseBiome(x >> 2, groundY.applyAsInt(x, z) >> 2, z >> 2)
                        .is(REFUSED_BIOMES));
    }
}
