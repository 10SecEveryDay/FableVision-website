package com.fablevision.client.seedfinder;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import com.fablevision.client.seedfinder.SeedCriteria.Dim;
import com.fablevision.client.seedfinder.SeedCriteria.Special;
import com.fablevision.client.seedfinder.SeedCriteria.StructureTarget;
import com.fablevision.client.seedfinder.SeedCriteria.ZombieMode;
import net.minecraft.core.Holder;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.levelgen.structure.StructureSet;
import net.minecraft.world.level.levelgen.structure.placement.RandomSpreadStructurePlacement;

/**
 * The FIXED, curated list of exactly what the finder searches for — the user's canonical
 * structure and biome sets, in their order, nothing more or less. Built against the live
 * registry so a spec only appears if its structure/biome actually exists in this version
 * (all vanilla ones do); datapack extras are intentionally NOT auto-added here.
 *
 * Each structure spec matches by id path + dimension, so one label can span several
 * structure sets (a "Surface Village" is five village sets) and several structures in a
 * set (Shipwreck = beached + sunken). Stronghold is flagged {@link Special} — it's found
 * by ring math and its own criterion, not placement. The list is overworld-only now:
 * Nether/End structures and the Dungeon were removed on request (see the note in the list).
 */
public final class SeedCatalog {

   /** One catalog row before it's resolved against the registry. */
   private record Spec(String label, Dim dim, int sampleY, Predicate<String> match,
                       ZombieMode zombie, Special special, String note) {
      static Spec structure(String label, Dim dim, int sampleY, Predicate<String> match) {
         return new Spec(label, dim, sampleY, match, ZombieMode.ANY, Special.NORMAL, "");
      }
   }

   private static Predicate<String> eq(String s) {
      return p -> p.equals(s);
   }

   private static Predicate<String> pre(String s) {
      return p -> p.startsWith(s);
   }

   // The list, in the user's order.
   private static final List<Spec> STRUCTURE_SPECS = List.of(
      Spec.structure("Surface Village", Dim.OVERWORLD, 64, pre("village")),
      Spec.structure("Pillager Outpost", Dim.OVERWORLD, 64, eq("pillager_outpost")),
      Spec.structure("Desert Pyramid", Dim.OVERWORLD, 64, eq("desert_pyramid")),
      Spec.structure("Jungle Pyramid", Dim.OVERWORLD, 64, eq("jungle_pyramid")),
      Spec.structure("Woodland Mansion", Dim.OVERWORLD, 64, eq("mansion")),
      Spec.structure("Swamp Hut", Dim.OVERWORLD, 64, eq("swamp_hut")),
      Spec.structure("Igloo", Dim.OVERWORLD, 64, eq("igloo")),
      Spec.structure("Ocean Monument", Dim.OVERWORLD, 64, eq("monument")),
      Spec.structure("Shipwreck", Dim.OVERWORLD, 64, pre("shipwreck")),
      Spec.structure("Ocean Ruins", Dim.OVERWORLD, 64, pre("ocean_ruin")),
      Spec.structure("Buried Treasure", Dim.OVERWORLD, 64, eq("buried_treasure")),
      Spec.structure("Trial Chambers", Dim.OVERWORLD, -20, eq("trial_chambers")),
      Spec.structure("Ancient City", Dim.OVERWORLD, -40, eq("ancient_city")),
      new Spec("Stronghold", Dim.OVERWORLD, 64, p -> false, ZombieMode.ANY, Special.STRONGHOLD, ""),
      Spec.structure("Mineshaft", Dim.OVERWORLD, 40, pre("mineshaft")),
      // Nether/End structures (Fortress, Bastion, Fossil, End City, End Ship) and Dungeon were
      // REMOVED from the list on the user's request (2026-07-14) — spawn is an overworld idea,
      // and those either measured oddly from the portal-in point or weren't seed-searchable.
      // Nether/End BIOMES stay. To bring one back, re-add its Spec line here; everything
      // (pickers, AI allowed-list, icons) follows this list automatically.
      new Spec("Ruined Portal", Dim.OVERWORLD, 64,
            p -> p.startsWith("ruined_portal") && !p.contains("nether"), ZombieMode.ANY, Special.NORMAL, ""),
      Spec.structure("Desert Well", Dim.OVERWORLD, 64, eq("desert_well")),
      Spec.structure("Trail Ruins", Dim.OVERWORLD, 64, eq("trail_ruins")),
      new Spec("Abandoned Village", Dim.OVERWORLD, 64, pre("village"), ZombieMode.ABANDONED, Special.NORMAL,
            "\"Abandoned\" = a zombie village (cobwebs, mossy blocks, zombie villagers) — predicted from the village's start piece; verify with /locate + a look.")
   );

   /**
    * Builds the structure picker list from the live registry, in spec order. A spec with
    * no matching structure in this world is skipped; STRONGHOLD/DUNGEON specials always
    * show. Surface Village is set to require a NORMAL start, Abandoned Village a zombie one.
    */
   public static List<StructureTarget> structures(WorldgenContext ctx) {
      // id path -> its set + spread + dimension (only RandomSpread sets are placeable this way).
      Map<String, Placement> byId = new LinkedHashMap<>();
      for (Holder.Reference<StructureSet> setRef : ctx.allSets) {
         if (!(setRef.value().placement() instanceof RandomSpreadStructurePlacement spread)) {
            continue;
         }
         Dim dim = dimensionOf(ctx, setRef);
         if (dim == null) {
            continue;
         }
         for (StructureSet.StructureSelectionEntry e : setRef.value().structures()) {
            Identifier id = e.structure().unwrapKey().map(ResourceKey::identifier).orElse(null);
            if (id != null) {
               byId.putIfAbsent(id.getPath(), new Placement(setRef, spread, dim, id));
            }
         }
      }

      List<StructureTarget> out = new ArrayList<>();
      for (Spec spec : STRUCTURE_SPECS) {
         if (spec.special() == Special.STRONGHOLD || spec.special() == Special.DUNGEON) {
            out.add(StructureTarget.special(spec.label(), spec.special(), spec.note()));
            continue;
         }
         List<Holder.Reference<StructureSet>> sets = new ArrayList<>();
         List<RandomSpreadStructurePlacement> spreads = new ArrayList<>();
         java.util.Set<Identifier> wanted = new java.util.HashSet<>();
         for (Placement pl : byId.values()) {
            if (pl.dim == spec.dim() && spec.match().test(pl.id.getPath())) {
               if (!sets.contains(pl.set)) {
                  sets.add(pl.set);
                  spreads.add(pl.spread);
               }
               wanted.add(pl.id);
            }
         }
         if (wanted.isEmpty()) {
            continue; // not present in this world
         }
         out.add(StructureTarget.build(spec.label(), spec.dim(), spec.sampleY(), 500,
               sets, spreads, wanted, spec.zombie(), spec.note()));
      }
      return out;
   }

   private static Dim dimensionOf(WorldgenContext ctx, Holder.Reference<StructureSet> setRef) {
      for (StructureSet.StructureSelectionEntry e : setRef.value().structures()) {
         for (Holder<Biome> b : e.structure().value().biomes()) {
            if (ctx.overworldBiomes.possibleBiomes().contains(b)) return Dim.OVERWORLD;
            if (ctx.netherBiomes.possibleBiomes().contains(b)) return Dim.NETHER;
            if (ctx.endBiomes.possibleBiomes().contains(b)) return Dim.END;
         }
      }
      return null;
   }

   private record Placement(Holder.Reference<StructureSet> set, RandomSpreadStructurePlacement spread,
                            Dim dim, Identifier id) {}

   // ── Biomes (the user's exact list, in order, with dimension) ──────────────

   private record BiomeSpec(String path, Dim dim) {}

   private static final List<BiomeSpec> BIOME_SPECS = buildBiomeSpecs();

   private static List<BiomeSpec> buildBiomeSpecs() {
      List<BiomeSpec> l = new ArrayList<>();
      String[] overworld = {
         "plains", "sunflower_plains", "forest", "flower_forest", "birch_forest", "old_growth_birch_forest",
         "dark_forest", "cherry_grove", "pale_garden", "taiga", "snowy_taiga", "old_growth_pine_taiga",
         "old_growth_spruce_taiga", "meadow", "grove", "snowy_slopes", "jagged_peaks", "frozen_peaks",
         "stony_peaks", "ice_spikes", "snowy_plains", "swamp", "mangrove_swamp", "jungle", "sparse_jungle",
         "bamboo_jungle", "desert", "savanna", "savanna_plateau", "windswept_savanna", "badlands",
         "wooded_badlands", "eroded_badlands", "ocean", "deep_ocean", "frozen_ocean", "deep_frozen_ocean",
         "cold_ocean", "deep_cold_ocean", "lukewarm_ocean", "deep_lukewarm_ocean", "warm_ocean", "river",
         "frozen_river", "beach", "snowy_beach", "stony_shore", "deep_dark", "dripstone_caves", "lush_caves"
      };
      for (String p : overworld) l.add(new BiomeSpec(p, Dim.OVERWORLD));
      String[] nether = {"nether_wastes", "soul_sand_valley", "crimson_forest", "warped_forest", "basalt_deltas"};
      for (String p : nether) l.add(new BiomeSpec(p, Dim.NETHER));
      String[] end = {"the_end", "small_end_islands", "end_midlands", "end_highlands", "end_barrens"};
      for (String p : end) l.add(new BiomeSpec(p, Dim.END));
      return l;
   }

   /** Biome ids for the picker, in the user's order, only those present in this world. */
   public static List<Identifier> biomes(WorldgenContext ctx) {
      List<Identifier> out = new ArrayList<>();
      for (BiomeSpec spec : BIOME_SPECS) {
         Identifier id = Identifier.withDefaultNamespace(spec.path());
         if (ctx.biomes.get(ResourceKey.create(net.minecraft.core.registries.Registries.BIOME, id)).isPresent()) {
            out.add(id);
         }
      }
      return out;
   }

   /** Which dimension a biome id belongs to (default OVERWORLD). */
   public static Dim biomeDim(Identifier id) {
      for (BiomeSpec spec : BIOME_SPECS) {
         if (spec.path().equals(id.getPath())) {
            return spec.dim();
         }
      }
      return Dim.OVERWORLD;
   }

   private SeedCatalog() {
   }
}
