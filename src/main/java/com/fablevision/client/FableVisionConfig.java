package com.fablevision.client;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.file.Files;
import java.nio.file.Path;
import net.fabricmc.loader.api.FabricLoader;

public final class FableVisionConfig {
   private static final Path FILE = FabricLoader.getInstance().getConfigDir().resolve("fablevision.json");
   public static boolean lowFire = true;
   public static boolean lowShield = true;
   public static boolean noExplosionParticles = true;
   public static boolean fullbright = true;
   public static boolean appleSkin = true;
   public static boolean tenSecTimer = false;
   public static boolean tenSecBig = true;
   public static String tenSecWorld = "";
   public static boolean autoLan = false;
   public static boolean lanServerNoticeShown = false;
   public static boolean helpShown = false; // first-launch welcome chat shown once
   public static boolean welcomeAnimShown = false; // legacy 1.27 splash flag (kept so old configs parse)
   public static boolean welcomeAnim2Shown = false; // the 1.31 finished-product splash — everyone sees it once
   public static boolean askCustomSpawn = true; // offer the seed-finder before creating a world
   public static boolean seedFastMode = false; // seed finder: search from 0,0 (fast) vs real spawn
   public static int seedFastDistance = 100; // fast mode only: how close to 0,0 (blocks)

   /** "Exact" (non-fast) means RIGHT where you'll spawn — 64 blocks from the real spawn
    *  point, close enough that it's in view the moment you load in (was 128, which could
    *  put the find a ~100-block walk away). Want distance instead? That's what Fast mode's
    *  distance picker is for. Neither mode ever moves your spawn. */
   public static final int SEED_EXACT_RADIUS = 64;
   /** Exact mode AIMS even tighter first: a hit with everything this close is taken
    *  instantly, while a looser (≤64) hit is parked as a backup and the search spends a
    *  few extra seconds hunting a truly-at-spawn seed before settling for the backup. */
   public static final int SEED_EXACT_TIGHT = 32;
   public static final int[] SEED_FAST_DISTANCES = {100, 200, 400, 800};

   public static int seedRadius() {
      return seedFastMode ? Math.max(48, seedFastDistance) : SEED_EXACT_RADIUS;
   }
   public static float fireOffset = 0.3F;
   public static float shieldOffset = 0.4F;
   public static float fullbrightIntensity = 1.0F;

   private FableVisionConfig() {
   }

   public static void setAll(boolean enabled) {
      lowFire = enabled;
      lowShield = enabled;
      noExplosionParticles = enabled;
      fullbright = enabled;
      appleSkin = enabled;
      if (!enabled) {
         tenSecTimer = false;
         autoLan = false;
      }
   }

   public static void load() {
      if (!Files.exists(FILE)) {
         save();
      } else {
         try {
            JsonObject json = JsonParser.parseString(Files.readString(FILE)).getAsJsonObject();
            if (json.has("lowFire")) {
               lowFire = json.get("lowFire").getAsBoolean();
            }

            if (json.has("lowShield")) {
               lowShield = json.get("lowShield").getAsBoolean();
            }

            if (json.has("noExplosionParticles")) {
               noExplosionParticles = json.get("noExplosionParticles").getAsBoolean();
            }

            if (json.has("fullbright")) {
               fullbright = json.get("fullbright").getAsBoolean();
            }

            if (json.has("appleSkin")) {
               appleSkin = json.get("appleSkin").getAsBoolean();
            }

            if (json.has("tenSecTimer")) {
               tenSecTimer = json.get("tenSecTimer").getAsBoolean();
            }

            if (json.has("tenSecBig")) {
               tenSecBig = json.get("tenSecBig").getAsBoolean();
            }

            if (json.has("tenSecWorld")) {
               tenSecWorld = json.get("tenSecWorld").getAsString();
            }

            if (json.has("autoLan")) {
               autoLan = json.get("autoLan").getAsBoolean();
            }

            if (json.has("lanServerNoticeShown")) {
               lanServerNoticeShown = json.get("lanServerNoticeShown").getAsBoolean();
            }

            if (json.has("helpShown")) {
               helpShown = json.get("helpShown").getAsBoolean();
            }

            if (json.has("welcomeAnimShown")) {
               welcomeAnimShown = json.get("welcomeAnimShown").getAsBoolean();
            }

            if (json.has("welcomeAnim2Shown")) {
               welcomeAnim2Shown = json.get("welcomeAnim2Shown").getAsBoolean();
            }

            if (json.has("askCustomSpawn")) {
               askCustomSpawn = json.get("askCustomSpawn").getAsBoolean();
            }

            if (json.has("seedFastMode")) {
               seedFastMode = json.get("seedFastMode").getAsBoolean();
            }

            if (json.has("seedFastDistance")) {
               seedFastDistance = clampInt(json.get("seedFastDistance").getAsInt(), 48, 2000);
            }

            if (json.has("fireOffset")) {
               fireOffset = clamp(json.get("fireOffset").getAsFloat(), 0.0F, 0.6F);
            }

            if (json.has("shieldOffset")) {
               shieldOffset = clamp(json.get("shieldOffset").getAsFloat(), 0.0F, 1.0F);
            }

            if (json.has("fullbrightIntensity")) {
               fullbrightIntensity = clamp(json.get("fullbrightIntensity").getAsFloat(), 0.0F, 1.0F);
            }
         } catch (Exception e) {
            FableVisionClient.LOGGER.warn("Could not read {}, using defaults", FILE, e);
         }
      }
   }

   public static void save() {
      JsonObject json = new JsonObject();
      json.addProperty("lowFire", lowFire);
      json.addProperty("lowShield", lowShield);
      json.addProperty("noExplosionParticles", noExplosionParticles);
      json.addProperty("fullbright", fullbright);
      json.addProperty("appleSkin", appleSkin);
      json.addProperty("tenSecTimer", tenSecTimer);
      json.addProperty("tenSecBig", tenSecBig);
      json.addProperty("tenSecWorld", tenSecWorld);
      json.addProperty("autoLan", autoLan);
      json.addProperty("lanServerNoticeShown", lanServerNoticeShown);
      json.addProperty("helpShown", helpShown);
      json.addProperty("welcomeAnimShown", welcomeAnimShown);
      json.addProperty("welcomeAnim2Shown", welcomeAnim2Shown);
      json.addProperty("askCustomSpawn", askCustomSpawn);
      json.addProperty("seedFastMode", seedFastMode);
      json.addProperty("seedFastDistance", seedFastDistance);
      json.addProperty("fireOffset", fireOffset);
      json.addProperty("shieldOffset", shieldOffset);
      json.addProperty("fullbrightIntensity", fullbrightIntensity);

      try {
         Files.writeString(FILE, new GsonBuilder().setPrettyPrinting().create().toJson(json));
      } catch (Exception e) {
         FableVisionClient.LOGGER.warn("Could not save {}", FILE, e);
      }
   }

   private static float clamp(float value, float min, float max) {
      return Math.max(min, Math.min(max, value));
   }

   private static int clampInt(int value, int min, int max) {
      return Math.max(min, Math.min(max, value));
   }
}
