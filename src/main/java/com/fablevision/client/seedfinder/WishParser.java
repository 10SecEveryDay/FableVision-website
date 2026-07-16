package com.fablevision.client.seedfinder;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import com.fablevision.client.FableVisionConfig;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;

/**
 * The AI side of the create-world wish flow — with a hard safety split:
 * the AI's ONLY job is translating the player's words into a JSON pick from lists WE
 * provide. Everything it returns is validated against the real catalog here; the seed
 * itself always comes from {@link SeedFinder}'s legit game-code search, never the AI.
 */
public final class WishParser {
   /** Either a usable criteria or a human-readable error — never both. {@code note} is
    *  the AI's honest "this part can't be seed-searched" flag, shown while searching. */
   public record Outcome(SeedCriteria criteria, String error, String note) {}

   /** Shown when a wish asks for a specific portal eye-count. That search isn't ready yet,
    *  so we find the nearest stronghold and tell the player it's coming as an update. */
   public static final String EYES_SOON = "✨ Portal-eye search releases THIS WEEK — nearest stronghold for now.";

   /** Shown whenever a wish or pick includes a stronghold: one can basically never generate
    *  at spawn (the nearest ring lives ~1300+ blocks out), so the search finds the closest. */
   public static final String STRONGHOLD_FAR =
         "🧱 A stronghold can NEVER be at spawn — the nearest ring is ~1300+ blocks out. Finding your closest one.";

   /** Builds the strict prompt: schema + the exact allowed names. Catalog must be ready. */
   public static String buildPrompt(String wish) {
      List<SeedCriteria.StructureTarget> catalog = SeedFinderScreen.catalogList();
      List<Identifier> biomes = SeedFinderScreen.biomeList();
      Set<String> labels = new HashSet<>();
      StringBuilder structureNames = new StringBuilder();
      for (SeedCriteria.StructureTarget t : catalog) {
         if (labels.add(t.label)) {
            if (structureNames.length() > 0) {
               structureNames.append(", ");
            }
            structureNames.append(t.label);
         }
      }
      StringBuilder biomeNames = new StringBuilder();
      for (Identifier b : biomes) {
         if (biomeNames.length() > 0) {
            biomeNames.append(", ");
         }
         biomeNames.append(b.getPath());
      }
      return "You convert a Minecraft world wish into strict JSON for a seed search. Reply with ONLY the JSON object - no explanation, no code fences.\n"
            + "Schema: {\"structures\":[{\"name\":\"<allowed structure name>\"}],"
            + "\"biomes\":[{\"name\":\"<allowed biome id>\",\"size\":\"any|big|huge\"}],"
            + "\"exclude\":[{\"name\":\"<allowed structure OR biome that must NOT be nearby>\"}],"
            + "\"stronghold\":<true or false>,"
            + "\"cant\":\"<empty, or a short plain note>\"}\n"
            + "Rules: do NOT include any distance or radius — the app decides how close. "
            + "'near / close to / beside / at spawn' just means include that thing — everything found ends up near spawn by definition. "
            + "For 'X next to Y' put BOTH X and Y in structures/biomes (they end up next to each other near spawn). "
            + "For 'X not next to Y' / 'X without Y nearby' / 'no Y around' put X (if any) in structures/biomes and Y in \"exclude\". "
            + "A wish that is ONLY about avoiding something ('no deserts near spawn') is valid: leave structures/biomes empty and just fill \"exclude\". "
            + "size is for biomes only and must be EXACTLY \"any\", \"big\" or \"huge\": map big/large to \"big\"; huge/giant/massive/ginormous to \"huge\"; small/tiny to \"any\" (small can't be checked — say so briefly in \"cant\"). Structures have no size; ignore size words on structures. "
            + "The exact number of eyes of ender already in a stronghold portal CANNOT be searched yet — if the wish asks for a certain eye count, set \"stronghold\" true so the nearest stronghold is found; you do NOT need to mention eyes in \"cant\" (the app shows its own note). "
            + "Only include things the wish actually asks for; NEVER invent a name not in the allowed lists - pick the closest allowed name instead. "
            + "If part of the wish truly cannot be seed-searched (chest loot, exact terrain shape, specific mob spawns), still fill in everything you CAN and note the rest briefly in \"cant\".\n"
            + "Allowed structure names: " + structureNames + "\n"
            + "Allowed biome ids: " + biomeNames + "\n"
            + "Wish: \"" + wish.replace('"', '\'') + "\"";
   }

   /** Validates the AI's reply into criteria. Unknown names come back as an error string. */
   public static Outcome parse(String aiReply) {
      if (aiReply == null || aiReply.startsWith("⚠") || aiReply.startsWith("No ") || aiReply.startsWith("Cooldown")) {
         return new Outcome(null, aiReply == null ? "No AI reply." : aiReply, null);
      }
      int open = aiReply.indexOf('{');
      int close = aiReply.lastIndexOf('}');
      if (open < 0 || close <= open) {
         return new Outcome(null, "The AI didn't answer with a search — try simpler words, or pick it yourself below.", null);
      }
      JsonObject root;
      try {
         root = JsonParser.parseString(aiReply.substring(open, close + 1)).getAsJsonObject();
      } catch (Exception e) {
         return new Outcome(null, "Couldn't read the AI's answer — try again, or pick it yourself below.", null);
      }

      List<SeedCriteria.StructureTarget> catalog = SeedFinderScreen.catalogList();
      List<Identifier> biomes = SeedFinderScreen.biomeList();
      if (catalog == null || biomes == null) {
         return new Outcome(null, "World-gen data is still loading — give it a second and try again.", null);
      }

      SeedCriteria criteria = new SeedCriteria();
      List<String> autoNotes = new ArrayList<>();
      try {
         if (root.has("structures") && root.get("structures").isJsonArray()) {
            JsonArray arr = root.getAsJsonArray("structures");
            Set<String> seen = new HashSet<>();
            for (var el : arr) {
               if (criteria.structures.size() >= 6) {
                  break;
               }
               JsonObject o = el.getAsJsonObject();
               String name = o.has("name") ? o.get("name").getAsString().trim() : "";
               int radius = FableVisionConfig.seedRadius();
               SeedCriteria.StructureTarget match = null;
               for (SeedCriteria.StructureTarget t : catalog) {
                  if (t.label.equalsIgnoreCase(name)) {
                     match = t;
                     break;
                  }
               }
               if (match == null) {
                  return new Outcome(null, "\"" + name + "\" isn't something I can search for — pick it yourself below.", null);
               }
               if (match.special == SeedCriteria.Special.STRONGHOLD) {
                  // Same as every other path: a stronghold can't be at spawn, so this is
                  // always a nearest-one search (the UI shows STRONGHOLD_FAR to say so).
                  criteria.strongholdRadius = 8000;
               } else if (match.special == SeedCriteria.Special.DUNGEON) {
                  if (!autoNotes.contains(match.note)) autoNotes.add(match.note);
               } else if (seen.add(match.label)) {
                  criteria.structures.add(match.withRadius(radius));
                  if (!match.note.isEmpty() && !autoNotes.contains(match.note)) autoNotes.add(match.note);
               }
            }
         }
         if (root.has("biomes") && root.get("biomes").isJsonArray()) {
            for (var el : root.getAsJsonArray("biomes")) {
               if (criteria.biomes.size() >= 3) {
                  break;
               }
               JsonObject o = el.getAsJsonObject();
               String err = addBiome(criteria, biomes,
                     o.has("name") ? o.get("name").getAsString() : "",
                     o.has("size") ? o.get("size").getAsString() : "any", autoNotes);
               if (err != null) {
                  return new Outcome(null, err, null);
               }
            }
         }
         // Older single-biome shape — some replies still use it.
         if (criteria.biomes.isEmpty() && root.has("biome")) {
            String err = addBiome(criteria, biomes, root.get("biome").getAsString(), "any", autoNotes);
            if (err != null) {
               return new Outcome(null, err, null);
            }
         }
         if (root.has("exclude") && root.get("exclude").isJsonArray()) {
            for (var el : root.getAsJsonArray("exclude")) {
               JsonObject o = el.getAsJsonObject();
               addExclude(criteria, catalog, biomes, o.has("name") ? o.get("name").getAsString() : "");
            }
         }
         if (root.has("stronghold")) {
            boolean want = false;
            try {
               var prim = root.get("stronghold").getAsJsonPrimitive();
               want = prim.isBoolean() ? prim.getAsBoolean()
                     : prim.isNumber() ? prim.getAsInt() > 0
                     : Boolean.parseBoolean(prim.getAsString());
            } catch (Exception ignored) {
            }
            if (want) {
               criteria.strongholdRadius = 8000; // strongholds are far by nature — find the nearest
            }
         }
      } catch (Exception e) {
         return new Outcome(null, "Couldn't read the AI's answer — try again, or pick it yourself below.", null);
      }

      String note = "";
      if (root.has("cant")) {
         try {
            note = root.get("cant").getAsString().trim();
         } catch (Exception ignored) {
         }
      }
      if (!note.isEmpty() && !autoNotes.contains(note)) {
         autoNotes.add(0, note);
      }
      String combined = String.join("  ", autoNotes);
      if (criteria.isEmpty()) {
         return new Outcome(null, combined.isEmpty()
               ? "The AI didn't find anything searchable in that wish — try naming a structure or biome."
               : "That can't be found by a seed search: " + combined, null);
      }
      return new Outcome(criteria, null, combined.isEmpty() ? null : combined);
   }

   /** Adds a "must NOT be nearby" target (structure or biome). Unknown names are ignored so
    *  one bad exclude never sinks the whole wish. Uses a wider buffer than the positive radius. */
   private static void addExclude(SeedCriteria criteria, List<SeedCriteria.StructureTarget> catalog, List<Identifier> allowed, String name) {
      if (name == null || name.isBlank()) {
         return;
      }
      int radius = Math.max(FableVisionConfig.seedRadius(), 256);
      String n = name.trim();
      for (SeedCriteria.StructureTarget t : catalog) {
         if (t.label.equalsIgnoreCase(n) && t.special == SeedCriteria.Special.NORMAL) {
            criteria.excludeStructures.add(t.withRadius(radius));
            return;
         }
      }
      String norm = n.toLowerCase(Locale.ROOT).replace("minecraft:", "").replace(' ', '_');
      for (Identifier b : allowed) {
         if (b.getPath().equals(norm)) {
            criteria.excludeBiomes.add(new SeedCriteria.BiomeTarget(
                  ResourceKey.create(Registries.BIOME, b), SeedFinderScreen.prettify(b.getPath()),
                  SeedCatalog.biomeDim(b), radius, 0));
            return;
         }
      }
   }

   /** Validates one biome pick into the criteria; returns an error message or null. */
   private static String addBiome(SeedCriteria criteria, List<Identifier> allowed, String name, String size, List<String> notes) {
      String norm = name == null ? "" : name.toLowerCase(Locale.ROOT).replace("minecraft:", "").replace(' ', '_').trim();
      if (norm.isEmpty()) {
         return null;
      }
      Identifier found = null;
      for (Identifier b : allowed) {
         if (b.getPath().equals(norm)) {
            found = b;
            break;
         }
      }
      if (found == null) {
         return "Biome \"" + name + "\" isn't searchable — pick it yourself below.";
      }
      for (SeedCriteria.BiomeTarget existing : criteria.biomes) {
         if (existing.biome.identifier().equals(found)) {
            return null;
         }
      }
      // The AI is told to use any|big|huge, but replies often echo the player's own word
      // ("large", "giant", "small") — map them here so size wishes ALWAYS take effect.
      String s = size == null ? "any" : size.toLowerCase(Locale.ROOT).trim();
      int minSpan = switch (s) {
         case "huge", "giant", "massive", "ginormous", "mega", "enormous" -> SeedCriteria.SPAN_HUGE;
         case "big", "large" -> SeedCriteria.SPAN_BIG;
         default -> 0;
      };
      if ((s.equals("small") || s.equals("tiny") || s.equals("little")) && notes != null) {
         String cant = "Biome size \"small\" can't be checked — any size is accepted.";
         if (!notes.contains(cant)) {
            notes.add(cant);
         }
      }
      criteria.biomes.add(new SeedCriteria.BiomeTarget(
            ResourceKey.create(Registries.BIOME, found), SeedFinderScreen.prettify(found.getPath()),
            SeedCatalog.biomeDim(found), FableVisionConfig.seedRadius(), minSpan));
      return null;
   }

   private WishParser() {
   }
}
