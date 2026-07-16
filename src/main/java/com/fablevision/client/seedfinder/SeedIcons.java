package com.fablevision.client.seedfinder;

import java.util.Locale;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * The picker icons. Real wiki photos can't be bundled (Minecraft's asset licence + the GUI
 * renders item stacks, not arbitrary images), so each structure/biome gets the item that
 * most reads as it — the block or item you'd picture for that place. Matched by the exact
 * catalog labels/biome ids in {@link SeedCatalog}.
 */
public final class SeedIcons {

   public static ItemStack structure(String label) {
      return switch (label) {
         case "Surface Village" -> stack(Items.BELL);
         case "Abandoned Village" -> stack(Items.COBWEB);
         case "Pillager Outpost" -> stack(Items.CROSSBOW);
         case "Desert Pyramid" -> stack(Items.SANDSTONE);
         case "Jungle Pyramid" -> stack(Items.CHISELED_STONE_BRICKS);
         case "Woodland Mansion" -> stack(Items.DARK_OAK_PLANKS);
         case "Swamp Hut" -> stack(Items.CAULDRON);
         case "Igloo" -> stack(Items.SNOW_BLOCK);
         case "Ocean Monument" -> stack(Items.PRISMARINE);
         case "Shipwreck" -> stack(Items.OAK_BOAT);
         case "Ocean Ruins" -> stack(Items.PRISMARINE_BRICKS);
         case "Buried Treasure" -> stack(Items.CHEST);
         case "Trial Chambers" -> stack(Items.TRIAL_KEY);
         case "Ancient City" -> stack(Items.ECHO_SHARD);
         case "Stronghold" -> stack(Items.ENDER_EYE);
         case "Mineshaft" -> stack(Items.RAIL);
         case "Dungeon" -> stack(Items.SPAWNER);
         case "Nether Fortress" -> stack(Items.NETHER_BRICKS);
         case "Bastion Remnant" -> stack(Items.GILDED_BLACKSTONE);
         case "Nether Fossil" -> stack(Items.BONE_BLOCK);
         case "End City" -> stack(Items.PURPUR_BLOCK);
         case "End Ship" -> stack(Items.ELYTRA);
         case "Ruined Portal" -> stack(Items.CRYING_OBSIDIAN);
         case "Desert Well" -> stack(Items.SMOOTH_SANDSTONE);
         case "Trail Ruins" -> stack(Items.SUSPICIOUS_GRAVEL);
         default -> stack(Items.FILLED_MAP);
      };
   }

   public static ItemStack biome(String path) {
      String p = path.toLowerCase(Locale.ROOT).replace("minecraft:", "");
      return switch (p) {
         // Plains / forests
         case "plains" -> stack(Items.GRASS_BLOCK);
         case "sunflower_plains" -> stack(Items.SUNFLOWER);
         case "forest" -> stack(Items.OAK_SAPLING);
         case "flower_forest" -> stack(Items.POPPY);
         case "birch_forest" -> stack(Items.BIRCH_SAPLING);
         case "old_growth_birch_forest" -> stack(Items.BIRCH_LOG);
         case "dark_forest" -> stack(Items.DARK_OAK_SAPLING);
         case "cherry_grove" -> stack(Items.CHERRY_SAPLING);
         case "pale_garden" -> stack(Items.PALE_OAK_SAPLING);
         // Taiga
         case "taiga" -> stack(Items.SPRUCE_SAPLING);
         case "snowy_taiga" -> stack(Items.SPRUCE_LOG);
         case "old_growth_pine_taiga" -> stack(Items.SPRUCE_LEAVES);
         case "old_growth_spruce_taiga" -> stack(Items.PODZOL);
         // Mountains / cold
         case "meadow" -> stack(Items.DANDELION);
         case "grove" -> stack(Items.SNOW);
         case "snowy_slopes" -> stack(Items.POWDER_SNOW_BUCKET);
         case "jagged_peaks" -> stack(Items.STONE);
         case "frozen_peaks" -> stack(Items.PACKED_ICE);
         case "stony_peaks" -> stack(Items.CALCITE);
         case "ice_spikes" -> stack(Items.PACKED_ICE);
         case "snowy_plains" -> stack(Items.SNOW_BLOCK);
         // Wet
         case "swamp" -> stack(Items.LILY_PAD);
         case "mangrove_swamp" -> stack(Items.MANGROVE_PROPAGULE);
         case "jungle" -> stack(Items.JUNGLE_SAPLING);
         case "sparse_jungle" -> stack(Items.JUNGLE_LEAVES);
         case "bamboo_jungle" -> stack(Items.BAMBOO);
         // Warm / dry
         case "desert" -> stack(Items.CACTUS);
         case "savanna" -> stack(Items.ACACIA_SAPLING);
         case "savanna_plateau" -> stack(Items.ACACIA_LOG);
         case "windswept_savanna" -> stack(Items.ACACIA_LEAVES);
         case "badlands" -> stack(Items.RED_SAND);
         case "wooded_badlands" -> stack(Items.RED_SANDSTONE);
         case "eroded_badlands" -> stack(Items.TERRACOTTA);
         // Water
         case "ocean" -> stack(Items.WATER_BUCKET);
         case "deep_ocean" -> stack(Items.HEART_OF_THE_SEA);
         case "frozen_ocean" -> stack(Items.ICE);
         case "deep_frozen_ocean" -> stack(Items.BLUE_ICE);
         case "cold_ocean" -> stack(Items.COD);
         case "deep_cold_ocean" -> stack(Items.SALMON);
         case "lukewarm_ocean" -> stack(Items.TROPICAL_FISH);
         case "deep_lukewarm_ocean" -> stack(Items.PUFFERFISH);
         case "warm_ocean" -> stack(Items.BRAIN_CORAL);
         case "river" -> stack(Items.CLAY_BALL);
         case "frozen_river" -> stack(Items.ICE);
         case "beach" -> stack(Items.SAND);
         case "snowy_beach" -> stack(Items.SNOWBALL);
         case "stony_shore" -> stack(Items.GRAVEL);
         // Caves
         case "deep_dark" -> stack(Items.SCULK);
         case "dripstone_caves" -> stack(Items.POINTED_DRIPSTONE);
         case "lush_caves" -> stack(Items.MOSS_BLOCK);
         // Nether
         case "nether_wastes" -> stack(Items.NETHERRACK);
         case "soul_sand_valley" -> stack(Items.SOUL_SAND);
         case "crimson_forest" -> stack(Items.CRIMSON_FUNGUS);
         case "warped_forest" -> stack(Items.WARPED_FUNGUS);
         case "basalt_deltas" -> stack(Items.BASALT);
         // End
         case "the_end" -> stack(Items.END_STONE);
         case "small_end_islands" -> stack(Items.CHORUS_FLOWER);
         case "end_midlands" -> stack(Items.END_STONE_BRICKS);
         case "end_highlands" -> stack(Items.CHORUS_FRUIT);
         case "end_barrens" -> stack(Items.PURPUR_BLOCK);
         default -> stack(Items.GRASS_BLOCK);
      };
   }

   private static ItemStack stack(net.minecraft.world.item.Item item) {
      return new ItemStack(item);
   }

   private SeedIcons() {
   }
}
