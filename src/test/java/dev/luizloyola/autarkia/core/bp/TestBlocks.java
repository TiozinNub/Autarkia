package dev.luizloyola.autarkia.core.bp;

import dev.luizloyola.autarkia.core.bp.Dictionary.BlockInfo;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A slice of vanilla's block registry, with the properties its default states really have, run
 * through {@link DictionaryRules} — so every binder test also exercises the derivation.
 */
final class TestBlocks {

    private static final String AXIS = "axis:x,y*,z";
    private static final String WATER = "waterlogged:true,false*";
    private static final String FACING = "facing:north*,south,west,east";
    private static final String STAIRS = FACING + ";half:top,bottom*;shape:straight*,inner_left,inner_right,"
            + "outer_left,outer_right;" + WATER;
    private static final String SLAB = "type:top,bottom*,double;" + WATER;
    private static final String DOOR = FACING + ";half:upper,lower*;hinge:left*,right;open:true,false*;"
            + "powered:true,false*";
    private static final String FENCE = "north:true,false*;east:true,false*;south:true,false*;west:true,false*;"
            + WATER;
    private static final String GATE = FACING + ";in_wall:true,false*;open:true,false*;powered:true,false*";
    private static final String TRAPDOOR = FACING + ";half:top,bottom*;open:true,false*;powered:true,false*;"
            + WATER;
    private static final String WALL = "up:true*,false;north:none*,low,tall;east:none*,low,tall;"
            + "south:none*,low,tall;west:none*,low,tall;" + WATER;
    private static final String BED = FACING + ";occupied:true,false*;part:head,foot*";

    /** Wall-hung blocks share their standing twin's item, as vanilla's {@code StandingAndWallBlockItem} does. */
    private static final Map<String, String> ITEM_OF = Map.of("wall_torch", "torch", "oak_wall_sign", "oak_sign",
            "oak_wall_hanging_sign", "oak_hanging_sign");

    /**
     * What vanilla's shapes stand half a block or taller, beyond the full cubes: a trapdoor, a carpet,
     * a torch, a button, a ladder and a sign do not.
     */
    private static final List<String> OBSTRUCTING = List.of("_stairs", "_slab", "_fence", "_fence_gate", "_wall",
            "_door", "_bed", "chest", "lantern", "_leaves", "dirt_path");

    private static final Map<String, BlockInfo> BLOCKS = new LinkedHashMap<>();

    static {
        for (String wood : List.of("oak", "spruce", "birch", "pale_oak", "crimson", "bamboo")) {
            boolean nether = wood.equals("crimson");
            boolean bamboo = wood.equals("bamboo");
            String log = nether ? "_stem" : bamboo ? "_block" : "_log";
            solid(wood + "_planks", "");
            solid(wood + log, AXIS);
            solid("stripped_" + wood + log, AXIS);
            if (!bamboo) {
                solid(wood + (nether ? "_hyphae" : "_wood"), AXIS);
            }
            open(wood + "_stairs", STAIRS);
            open(wood + "_slab", SLAB);
            open(wood + "_door", DOOR);
            open(wood + "_fence", FENCE);
            open(wood + "_trapdoor", TRAPDOOR);
            if (!wood.equals("pale_oak")) {
                open(wood + "_fence_gate", GATE);
            }
        }
        solid("bamboo_mosaic", "");
        open("bamboo_mosaic_stairs", STAIRS);
        for (String colour : DictionaryRules.COLOURS) {
            solid(colour + "_wool", "");
            open(colour + "_carpet", "");
            open(colour + "_bed", BED);
        }
        solid("blue_ice", "");
        put("red_sand", true, 0, true, "");
        for (String stone : List.of("stone", "cobblestone", "granite", "polished_granite")) {
            solid(stone, "");
            open(stone + "_stairs", STAIRS);
            open(stone + "_slab", SLAB);
        }
        open("stone_button", FACING + ";face:floor,wall*,ceiling;powered:true,false*");
        open("stone_pressure_plate", "powered:true,false*");
        open("cobblestone_wall", WALL);
        solid("mossy_cobblestone", "");
        solid("smooth_stone", "");
        open("smooth_stone_slab", SLAB);
        solid("stone_bricks", "");
        open("stone_brick_stairs", STAIRS);
        open("stone_brick_slab", SLAB);
        open("stone_brick_wall", WALL);
        solid("chiseled_stone_bricks", "");
        solid("cracked_stone_bricks", "");
        solid("mossy_stone_bricks", "");
        open("mossy_stone_brick_stairs", STAIRS);
        solid("quartz_block", "");
        open("quartz_stairs", STAIRS);
        open("quartz_slab", SLAB);
        solid("chiseled_quartz_block", "");
        solid("bricks", "");
        open("brick_stairs", STAIRS);
        open("brick_wall", WALL);
        for (String ground : List.of("dirt", "coarse_dirt", "glass", "obsidian", "crafting_table")) {
            solid(ground, "");
        }
        solid("furnace", FACING + ";lit:true,false*");
        solid("grass_block", "snowy:true,false*");
        solid("podzol", "snowy:true,false*");
        open("dirt_path", "");
        put("gravel", true, 0, true, "");
        put("sand", true, 0, true, "");
        put("torch", false, 14, false, "");
        put("wall_torch", false, 14, false, FACING);
        put("lantern", false, 15, false, "hanging:true,false*;" + WATER);
        open("ladder", FACING + ";" + WATER);
        open("oak_sign", "rotation:0*,1,2,3,4,5,6,7,8,9,10,11,12,13,14,15;" + WATER);
        open("oak_wall_sign", FACING + ";" + WATER);
        open("oak_hanging_sign", "rotation:0*,1,2,3,4,5,6,7,8,9,10,11,12,13,14,15;attached:true,false*;" + WATER);
        open("oak_wall_hanging_sign", FACING + ";" + WATER);
        open("chest", FACING + ";type:single*,left,right;" + WATER);
        open("oak_leaves", "distance:1,2,3,4,5,6,7*;persistent:true,false*;" + WATER);
        open("tall_grass", "half:upper,lower*");
        open("air", "");
    }

    private TestBlocks() {
    }

    private static void solid(String id, String props) {
        put(id, true, 0, false, props);
    }

    private static void open(String id, String props) {
        put(id, false, 0, false, props);
    }

    private static void put(String id, boolean solid, int light, boolean falls, String props) {
        Map<String, List<String>> properties = new LinkedHashMap<>();
        Map<String, String> defaults = new HashMap<>();
        for (String prop : props.split(";")) {
            if (prop.isEmpty()) {
                continue;
            }
            String[] kv = prop.split(":");
            List<String> values = new ArrayList<>();
            for (String value : kv[1].split(",")) {
                boolean isDefault = value.endsWith("*");
                String bare = isDefault ? value.substring(0, value.length() - 1) : value;
                values.add(bare);
                if (isDefault) {
                    defaults.put(kv[0], bare);
                }
            }
            properties.put(kv[0], values);
        }
        String qualified = "minecraft:" + id;
        String item = id.equals("air") ? "" : "minecraft:" + ITEM_OF.getOrDefault(id, id);
        BLOCKS.put(qualified, new BlockInfo(qualified, properties, defaults, solid,
                solid || OBSTRUCTING.stream().anyMatch(id::endsWith), light, falls, item));
    }

    static Map<String, BlockInfo> blocks() {
        return BLOCKS;
    }

    static DictionaryRules.Derived derived() {
        return DictionaryRules.derive(BLOCKS.values(), Set.of("minecraft:crimson_planks"));
    }

    static Dictionary dictionary() {
        return derived().dictionary();
    }
}
