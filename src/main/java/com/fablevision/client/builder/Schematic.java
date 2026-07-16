package com.fablevision.client.builder;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;

/**
 * FableVision's own schematic reader. Parses downloaded schematic files with only
 * Minecraft's built-in NBT classes — no Litematica, no WorldEdit, no external mod.
 *
 * Supported formats (drop the files in config/fablevision/schematics/):
 *   .schem      Sponge Schematic v2 + v3 (WorldEdit) — the modern standard.
 *   .litematic  Litematica (multi-region, bit-packed) — parsed directly.
 *   .schematic  Legacy MCEdit (pre-1.13 numeric IDs) — best-effort via a built-in
 *               flattening table; unmapped blocks are skipped and reported.
 *   .nbt        Vanilla structure files — still readable for compatibility.
 *
 * All formats are gzip-compressed NBT and end up as {Name, Properties} block states,
 * which run through the same NbtUtils.readBlockState path. Unknown blocks degrade to
 * air (skipped) rather than throwing, so a slightly-off file never crashes the game.
 */
public final class Schematic {
   /** One block to place: position relative to the origin + the exact state. */
   public record Placement(BlockPos pos, BlockState state) {
   }

   /** Hard cap so a giant download can't exhaust memory. */
   private static final int MAX_BLOCKS = 2_000_000;

   public final String name;
   public final int sizeX;
   public final int sizeY;
   public final int sizeZ;
   public final List<Placement> placements; // air stripped, sorted bottom-up
   public final String note; // optional heads-up (e.g. legacy blocks skipped), or null
   private List<ItemNeed> materialsCache; // computed once, immutable placements

   private Schematic(String name, int sx, int sy, int sz, List<Placement> placements, String note) {
      this.name = name;
      this.sizeX = sx;
      this.sizeY = sy;
      this.sizeZ = sz;
      this.placements = placements;
      this.note = note;
   }

   /** One line of the bill of materials: the item you collect (for its icon + name) and how
    *  many you need. {@code item} is {@code Items.AIR} for blocks with no obtainable item. */
   public record ItemNeed(Item item, String label, int count) {
   }

   /** Bill of materials, grouped by the item you'd actually collect (water/lava → their bucket),
    *  most-needed first. Cached — placements never change. */
   public List<ItemNeed> materials() {
      if (materialsCache != null) return materialsCache;
      Map<String, Integer> counts = new HashMap<>();
      Map<String, Item> icons = new HashMap<>();
      Map<String, String> labels = new HashMap<>();
      for (Placement p : placements) {
         Item it = placementItem(p.state());
         String key;
         String label;
         if (it == Items.AIR) { // no obtainable item (e.g. fire) — list by block name, no icon
            label = p.state().getBlock().getName().getString() + " (no item)";
            key = "noitem:" + label;
         } else {
            key = BuiltInRegistries.ITEM.getKey(it).toString();
            label = new ItemStack(it).getHoverName().getString();
         }
         counts.merge(key, 1, Integer::sum);
         icons.putIfAbsent(key, it);
         labels.putIfAbsent(key, label);
      }
      List<ItemNeed> list = new ArrayList<>(counts.size());
      for (Map.Entry<String, Integer> e : counts.entrySet()) {
         list.add(new ItemNeed(icons.get(e.getKey()), labels.get(e.getKey()), e.getValue()));
      }
      list.sort(Comparator.comparingInt(ItemNeed::count).reversed().thenComparing(ItemNeed::label));
      materialsCache = List.copyOf(list);
      return materialsCache;
   }

   /** The item you'd actually gather to place this block: water/lava need their bucket, most
    *  blocks are simply their own item. {@code Items.AIR} means there's no obtainable item for
    *  it (e.g. fire), so the auto-builder skips it. */
   public static Item placementItem(BlockState state) {
      Block b = state.getBlock();
      if (b == Blocks.WATER) return Items.WATER_BUCKET;
      if (b == Blocks.LAVA) return Items.LAVA_BUCKET;
      return b.asItem();
   }

   /** Loads any supported schematic. Throws with a human-readable message on bad files. */
   public static Schematic load(Path file) throws Exception {
      String fn = file.getFileName().toString();
      String lower = fn.toLowerCase(Locale.ROOT);
      CompoundTag root = readNbt(file);

      if (lower.endsWith(".schem")) return loadSponge(fn, root);
      if (lower.endsWith(".litematic")) return loadLitematica(fn, root);
      if (lower.endsWith(".schematic")) return loadLegacy(fn, root);
      if (lower.endsWith(".nbt")) return loadStructureNbt(fn, root);

      // Unknown extension — sniff the contents so a renamed file still works.
      if (root.contains("Regions")) return loadLitematica(fn, root);
      if (root.getCompound("Schematic").isPresent() || root.contains("BlockData")) return loadSponge(fn, root);
      if (root.contains("size") && root.contains("palette")) return loadStructureNbt(fn, root);
      if (root.contains("Blocks")) return loadLegacy(fn, root);
      throw new IllegalArgumentException("Unsupported file '" + fn + "'. Use .schem, .litematic, .schematic, or .nbt.");
   }

   /** Reads gzip NBT, falling back to uncompressed NBT (some tools export raw). */
   private static CompoundTag readNbt(Path file) throws Exception {
      try {
         return NbtIo.readCompressed(file, NbtAccounter.unlimitedHeap());
      } catch (Exception compressed) {
         try {
            return NbtIo.read(file);
         } catch (Exception raw) {
            throw new IllegalArgumentException("Couldn't read '" + file.getFileName()
                  + "' as NBT — it may be corrupt or not a schematic.");
         }
      }
   }

   // ── Sponge .schem (WorldEdit) — v2 flat, v3 nested under "Schematic" ─────
   private static Schematic loadSponge(String name, CompoundTag root) {
      CompoundTag base = root.getCompound("Schematic").orElse(root); // v3 nests, v2 is flat
      int version = base.getIntOr("Version", 2);
      int width = base.getShortOr("Width", (short) 0) & 0xFFFF;
      int height = base.getShortOr("Height", (short) 0) & 0xFFFF;
      int length = base.getShortOr("Length", (short) 0) & 0xFFFF;
      if (width <= 0 || height <= 0 || length <= 0) {
         throw new IllegalArgumentException("Sponge .schem has an empty size (" + width + "x" + height + "x" + length + ").");
      }

      CompoundTag palette;
      byte[] blockData;
      if (version >= 3) {
         CompoundTag blocks = base.getCompoundOrEmpty("Blocks");
         palette = blocks.getCompoundOrEmpty("Palette");
         blockData = blocks.getByteArray("Data").orElse(new byte[0]);
      } else {
         palette = base.getCompoundOrEmpty("Palette");
         blockData = base.getByteArray("BlockData").orElse(new byte[0]);
      }
      if (palette.isEmpty() || blockData.length == 0) {
         throw new IllegalArgumentException("Sponge .schem (v" + version + ") has no block data.");
      }

      int max = 0;
      for (String key : palette.keySet()) max = Math.max(max, palette.getIntOr(key, 0));
      BlockState[] byId = new BlockState[max + 1];
      for (String key : palette.keySet()) {
         int id = palette.getIntOr(key, -1);
         if (id >= 0 && id < byId.length) byId[id] = blockStateFromString(key);
      }

      // BlockData is a stream of unsigned LEB128 varints, one per block, X fastest then Z then Y.
      List<Placement> out = new ArrayList<>();
      int area = width * length;
      int pos = 0;
      int i = 0;
      while (pos < blockData.length) {
         int value = 0;
         int shift = 0;
         while (true) {
            byte b = blockData[pos++];
            value |= (b & 0x7F) << shift;
            if ((b & 0x80) == 0) break;
            shift += 7;
            if (shift > 35 || pos >= blockData.length) {
               throw new IllegalArgumentException("Corrupt Sponge block data.");
            }
         }
         int x = i % width;
         int z = (i / width) % length;
         int y = i / area;
         if (value >= 0 && value < byId.length) addBlock(out, x, y, z, byId[value]);
         i++;
      }
      return finish(name, out, null);
   }

   // ── Litematica .litematic — one or more regions, bit-packed long array ────
   private static Schematic loadLitematica(String name, CompoundTag root) {
      CompoundTag regions = root.getCompoundOrEmpty("Regions");
      if (regions.isEmpty()) throw new IllegalArgumentException("This .litematic has no Regions.");

      List<Placement> out = new ArrayList<>();
      for (String regionName : regions.keySet()) {
         CompoundTag region = regions.getCompoundOrEmpty(regionName);
         CompoundTag posT = region.getCompoundOrEmpty("Position");
         CompoundTag sizeT = region.getCompoundOrEmpty("Size");
         int px = posT.getIntOr("x", 0), py = posT.getIntOr("y", 0), pz = posT.getIntOr("z", 0);
         int sxRaw = sizeT.getIntOr("x", 0), syRaw = sizeT.getIntOr("y", 0), szRaw = sizeT.getIntOr("z", 0);
         int ax = Math.abs(sxRaw), ay = Math.abs(syRaw), az = Math.abs(szRaw);
         if (ax == 0 || ay == 0 || az == 0) continue;
         // A negative Size means the region extends in the negative direction from Position,
         // but the block array is ALWAYS indexed from the region's minimum corner (this is
         // what Litematica itself does). Mirroring the coordinates instead would flip the
         // build along that axis — blocks in the wrong spots with un-flipped facings.
         int minX = px + (sxRaw < 0 ? sxRaw + 1 : 0);
         int minY = py + (syRaw < 0 ? syRaw + 1 : 0);
         int minZ = pz + (szRaw < 0 ? szRaw + 1 : 0);

         ListTag pal = region.getListOrEmpty("BlockStatePalette");
         if (pal.isEmpty()) continue;
         BlockState[] states = new BlockState[pal.size()];
         for (int k = 0; k < pal.size(); k++) states[k] = readBlockStateSafe(pal.getCompoundOrEmpty(k));

         long[] data = region.getLongArray("BlockStates").orElse(new long[0]);
         if (data.length == 0) continue;
         int bits = Math.max(2, 32 - Integer.numberOfLeadingZeros(Math.max(1, pal.size() - 1)));
         long mask = (1L << bits) - 1;

         // Litematica index order: x fastest, then z, then y.
         for (int y = 0; y < ay; y++) {
            for (int z = 0; z < az; z++) {
               for (int x = 0; x < ax; x++) {
                  int index = (y * az + z) * ax + x;
                  int value = (int) readPacked(data, index, bits, mask);
                  if (value < 0 || value >= states.length) continue;
                  addBlock(out, minX + x, minY + y, minZ + z, states[value]);
               }
            }
         }
      }
      return finish(name, out, null);
   }

   /** Reads a `bits`-wide value at `index` from a packed long array (values may span two longs). */
   private static long readPacked(long[] arr, int index, int bits, long mask) {
      long bitStart = (long) index * bits;
      int startIdx = (int) (bitStart >> 6);
      int endIdx = (int) (((long) (index + 1) * bits - 1) >> 6);
      int offset = (int) (bitStart & 63);
      if (startIdx < 0 || endIdx >= arr.length) return 0; // corrupt/short array → palette[0] (air)
      if (startIdx == endIdx) {
         return (arr[startIdx] >>> offset) & mask;
      }
      return ((arr[startIdx] >>> offset) | (arr[endIdx] << (64 - offset))) & mask;
   }

   // ── Legacy .schematic (MCEdit) — numeric IDs, best-effort flattening ─────
   private static Schematic loadLegacy(String name, CompoundTag root) {
      CompoundTag base = root.getCompound("Schematic").orElse(root);
      int width = base.getShortOr("Width", (short) 0) & 0xFFFF;
      int height = base.getShortOr("Height", (short) 0) & 0xFFFF;
      int length = base.getShortOr("Length", (short) 0) & 0xFFFF;
      byte[] blocks = base.getByteArray("Blocks").orElse(new byte[0]);
      byte[] dataArr = base.getByteArray("Data").orElse(new byte[0]);
      byte[] add = base.getByteArray("AddBlocks").orElse(base.getByteArray("Add").orElse(new byte[0]));
      if (width <= 0 || height <= 0 || length <= 0 || blocks.length == 0) {
         throw new IllegalArgumentException("Legacy .schematic has no blocks.");
      }

      List<Placement> out = new ArrayList<>();
      int area = width * length;
      int unmapped = 0;
      for (int i = 0; i < blocks.length; i++) {
         int id = blocks[i] & 0xFF;
         if (add.length > (i >> 1)) {
            int addByte = add[i >> 1] & 0xFF;
            int hi = (i & 1) == 0 ? (addByte & 0x0F) : (addByte >> 4);
            id |= hi << 8;
         }
         if (id == 0) continue; // air
         int data = i < dataArr.length ? (dataArr[i] & 0x0F) : 0;
         String state = LegacyMap.state(id, data);
         if (state == null) { unmapped++; continue; }
         int x = i % width;
         int z = (i / width) % length;
         int y = i / area;
         addBlock(out, x, y, z, blockStateFromString(state));
      }
      String note = unmapped > 0
            ? unmapped + " legacy block(s) had no modern mapping and were skipped. For full accuracy, convert this .schematic to .schem."
            : null;
      return finish(name, out, note);
   }

   // ── Vanilla structure .nbt (kept for compatibility) ─────────────────────
   private static Schematic loadStructureNbt(String name, CompoundTag root) {
      ListTag sizeTag = root.getList("size").orElse(null);
      if (sizeTag == null || sizeTag.size() != 3) {
         throw new IllegalArgumentException("Not a structure .nbt file (missing 'size').");
      }
      int sx = sizeTag.getIntOr(0, 0), sy = sizeTag.getIntOr(1, 0), sz = sizeTag.getIntOr(2, 0);
      if (sx <= 0 || sy <= 0 || sz <= 0) {
         throw new IllegalArgumentException("Structure has an empty size (" + sx + "x" + sy + "x" + sz + ").");
      }

      ListTag palette = root.getList("palette").orElse(null);
      if (palette == null) {
         ListTag palettes = root.getList("palettes").orElse(null);
         if (palettes != null && palettes.size() > 0 && palettes.get(0) instanceof ListTag first) {
            palette = first;
         }
      }
      if (palette == null) throw new IllegalArgumentException("Not a structure .nbt file (missing 'palette').");

      BlockState[] states = new BlockState[palette.size()];
      for (int i = 0; i < palette.size(); i++) states[i] = readBlockStateSafe(palette.getCompoundOrEmpty(i));

      ListTag blocks = root.getListOrEmpty("blocks");
      List<Placement> out = new ArrayList<>();
      for (int i = 0; i < blocks.size(); i++) {
         CompoundTag b = blocks.getCompoundOrEmpty(i);
         ListTag pos = b.getListOrEmpty("pos");
         if (pos.size() != 3) continue;
         int stateIdx = b.getInt("state").orElse(-1);
         if (stateIdx < 0 || stateIdx >= states.length) continue;
         addBlock(out, pos.getIntOr(0, 0), pos.getIntOr(1, 0), pos.getIntOr(2, 0), states[stateIdx]);
      }
      return finish(name, out, null);
   }

   // ── Shared helpers ──────────────────────────────────────────────────────

   /** Adds a placement, skipping air and stripping waterlogging (never conjure free water). */
   private static void addBlock(List<Placement> out, int x, int y, int z, BlockState state) {
      if (state == null || state.isAir()) return;
      // FLOWING water/lava (level != 0) re-forms from the source blocks all by itself —
      // don't stage it, don't list it, don't pay a bucket for it (it would evaporate anyway).
      Block b = state.getBlock();
      if ((b == Blocks.WATER || b == Blocks.LAVA)
            && state.hasProperty(BlockStateProperties.LEVEL)
            && state.getValue(BlockStateProperties.LEVEL) != 0) {
         return;
      }
      if (state.hasProperty(BlockStateProperties.WATERLOGGED)) {
         state = state.setValue(BlockStateProperties.WATERLOGGED, Boolean.FALSE);
      }
      out.add(new Placement(new BlockPos(x, y, z), state));
   }

   /** Normalises to a 0-based grid, sorts bottom-up, and validates. */
   private static Schematic finish(String name, List<Placement> raw, String note) {
      if (raw.isEmpty()) {
         throw new IllegalArgumentException("This schematic has no placeable blocks (after skipping air/unknown).");
      }
      if (raw.size() > MAX_BLOCKS) {
         throw new IllegalArgumentException("Schematic is too large (" + raw.size()
               + " blocks, max " + MAX_BLOCKS + "). Try a smaller build.");
      }

      int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
      int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
      for (Placement p : raw) {
         BlockPos q = p.pos();
         minX = Math.min(minX, q.getX()); maxX = Math.max(maxX, q.getX());
         minY = Math.min(minY, q.getY()); maxY = Math.max(maxY, q.getY());
         minZ = Math.min(minZ, q.getZ()); maxZ = Math.max(maxZ, q.getZ());
      }

      List<Placement> norm = new ArrayList<>(raw.size());
      for (Placement p : raw) {
         BlockPos q = p.pos();
         norm.add(new Placement(new BlockPos(q.getX() - minX, q.getY() - minY, q.getZ() - minZ), p.state()));
      }
      // Build bottom-up, layer by layer, so it looks natural and blocks attach correctly.
      norm.sort(Comparator
            .comparingInt((Placement p) -> p.pos().getY())
            .thenComparingInt(p -> p.pos().getX())
            .thenComparingInt(p -> p.pos().getZ()));

      return new Schematic(name, maxX - minX + 1, maxY - minY + 1, maxZ - minZ + 1, List.copyOf(norm), note);
   }

   /** "minecraft:oak_stairs[facing=east,half=bottom]" → BlockState, via a {Name,Properties} tag. */
   private static BlockState blockStateFromString(String s) {
      String blockName;
      CompoundTag props = null;
      int open = s.indexOf('[');
      if (open >= 0) {
         blockName = s.substring(0, open).trim();
         int close = s.indexOf(']', open);
         String inside = s.substring(open + 1, close < 0 ? s.length() : close);
         props = new CompoundTag();
         for (String kv : inside.split(",")) {
            int eq = kv.indexOf('=');
            if (eq <= 0) continue;
            props.putString(kv.substring(0, eq).trim(), kv.substring(eq + 1).trim());
         }
      } else {
         blockName = s.trim();
      }
      CompoundTag tag = new CompoundTag();
      tag.putString("Name", blockName);
      if (props != null && !props.isEmpty()) tag.put("Properties", props);
      return readBlockStateSafe(tag);
   }

   /** readBlockState that never throws — unknown/invalid states become air (skipped). */
   private static BlockState readBlockStateSafe(CompoundTag tag) {
      try {
         return NbtUtils.readBlockState(BuiltInRegistries.BLOCK, tag);
      } catch (Exception e) {
         return Blocks.AIR.defaultBlockState();
      }
   }

   /**
    * Best-effort pre-1.13 (id, data) → modern block-state string. Covers common building
    * blocks; returns null for anything unmapped so the caller can skip and report it.
    */
   private static final class LegacyMap {
      private static final String[] COLORS = {"white", "orange", "magenta", "light_blue", "yellow", "lime",
            "pink", "gray", "light_gray", "cyan", "purple", "blue", "brown", "green", "red", "black"};
      private static final String[] PLANK = {"oak", "spruce", "birch", "jungle", "acacia", "dark_oak"};
      private static final Map<Integer, String> SIMPLE = new HashMap<>();
      private static final Map<Integer, String> STAIRS = new HashMap<>();

      static {
         // Data-insensitive blocks (data ignored).
         SIMPLE.put(2, "grass_block"); SIMPLE.put(4, "cobblestone"); SIMPLE.put(7, "bedrock");
         SIMPLE.put(13, "gravel"); SIMPLE.put(14, "gold_ore"); SIMPLE.put(15, "iron_ore");
         SIMPLE.put(16, "coal_ore"); SIMPLE.put(19, "sponge"); SIMPLE.put(20, "glass");
         SIMPLE.put(21, "lapis_ore"); SIMPLE.put(22, "lapis_block"); SIMPLE.put(41, "gold_block");
         SIMPLE.put(42, "iron_block"); SIMPLE.put(45, "bricks"); SIMPLE.put(46, "tnt");
         SIMPLE.put(47, "bookshelf"); SIMPLE.put(48, "mossy_cobblestone"); SIMPLE.put(49, "obsidian");
         SIMPLE.put(56, "diamond_ore"); SIMPLE.put(57, "diamond_block"); SIMPLE.put(58, "crafting_table");
         SIMPLE.put(73, "redstone_ore"); SIMPLE.put(74, "redstone_ore"); SIMPLE.put(79, "ice");
         SIMPLE.put(80, "snow_block"); SIMPLE.put(82, "clay"); SIMPLE.put(84, "jukebox");
         SIMPLE.put(85, "oak_fence"); SIMPLE.put(87, "netherrack"); SIMPLE.put(88, "soul_sand");
         SIMPLE.put(89, "glowstone"); SIMPLE.put(101, "iron_bars"); SIMPLE.put(102, "glass_pane");
         SIMPLE.put(103, "melon"); SIMPLE.put(110, "mycelium"); SIMPLE.put(112, "nether_bricks");
         SIMPLE.put(113, "nether_brick_fence"); SIMPLE.put(121, "end_stone"); SIMPLE.put(123, "redstone_lamp");
         SIMPLE.put(129, "emerald_ore"); SIMPLE.put(133, "emerald_block"); SIMPLE.put(152, "redstone_block");
         SIMPLE.put(153, "nether_quartz_ore"); SIMPLE.put(165, "slime_block"); SIMPLE.put(172, "terracotta");
         SIMPLE.put(173, "coal_block"); SIMPLE.put(174, "packed_ice"); SIMPLE.put(188, "spruce_fence");
         SIMPLE.put(189, "birch_fence"); SIMPLE.put(190, "jungle_fence"); SIMPLE.put(191, "dark_oak_fence");
         SIMPLE.put(192, "acacia_fence"); SIMPLE.put(201, "purpur_block"); SIMPLE.put(206, "end_stone_bricks");
         SIMPLE.put(214, "nether_wart_block"); SIMPLE.put(216, "bone_block");

         // Stairs (facing/half derived from data).
         STAIRS.put(53, "oak_stairs"); STAIRS.put(67, "cobblestone_stairs"); STAIRS.put(108, "brick_stairs");
         STAIRS.put(109, "stone_brick_stairs"); STAIRS.put(114, "nether_brick_stairs");
         STAIRS.put(128, "sandstone_stairs"); STAIRS.put(134, "spruce_stairs"); STAIRS.put(135, "birch_stairs");
         STAIRS.put(136, "jungle_stairs"); STAIRS.put(156, "quartz_stairs"); STAIRS.put(163, "acacia_stairs");
         STAIRS.put(164, "dark_oak_stairs"); STAIRS.put(180, "red_sandstone_stairs"); STAIRS.put(203, "purpur_stairs");
      }

      static String state(int id, int data) {
         switch (id) {
            case 1: return switch (data) {
               case 1 -> "granite"; case 2 -> "polished_granite"; case 3 -> "diorite";
               case 4 -> "polished_diorite"; case 5 -> "andesite"; case 6 -> "polished_andesite";
               default -> "stone";
            };
            case 3: return switch (data) { case 1 -> "coarse_dirt"; case 2 -> "podzol"; default -> "dirt"; };
            case 5: return PLANK[data % PLANK.length] + "_planks";
            case 12: return data == 1 ? "red_sand" : "sand";
            case 17: return log(data, false);
            case 162: return log(data, true);
            case 18: return leaves(data, false);
            case 161: return leaves(data, true);
            case 24: return switch (data) { case 1 -> "chiseled_sandstone"; case 2 -> "cut_sandstone"; default -> "sandstone"; };
            case 179: return switch (data) { case 1 -> "chiseled_red_sandstone"; case 2 -> "cut_red_sandstone"; default -> "red_sandstone"; };
            case 35: return COLORS[data & 15] + "_wool";
            case 95: return COLORS[data & 15] + "_stained_glass";
            case 160: return COLORS[data & 15] + "_stained_glass_pane";
            case 159: return COLORS[data & 15] + "_terracotta";
            case 171: return COLORS[data & 15] + "_carpet";
            case 251: return COLORS[data & 15] + "_concrete";
            case 252: return COLORS[data & 15] + "_concrete_powder";
            case 98: return switch (data) {
               case 1 -> "mossy_stone_bricks"; case 2 -> "cracked_stone_bricks"; case 3 -> "chiseled_stone_bricks";
               default -> "stone_bricks";
            };
            case 155: return switch (data) { case 1 -> "chiseled_quartz_block"; case 2 -> "quartz_pillar"; default -> "quartz_block"; };
            case 168: return switch (data) { case 1 -> "prismarine_bricks"; case 2 -> "dark_prismarine"; default -> "prismarine"; };
            case 44: return stoneSlab(data);
            case 126: return PLANK[(data & 7) % PLANK.length] + "_slab" + slabHalf(data);
            case 182: return "red_sandstone_slab" + slabHalf(data);
            case 139: return data == 1 ? "mossy_cobblestone_wall" : "cobblestone_wall";
            default:
               String stair = STAIRS.get(id);
               if (stair != null) return stair + stairProps(data);
               return SIMPLE.get(id);
         }
      }

      private static String log(int data, boolean log2) {
         String wood = log2 ? (data & 1) == 0 ? "acacia" : "dark_oak"
               : PLANK[data & 3];
         return switch (data & 0xC) {
            case 4 -> wood + "_log[axis=x]";
            case 8 -> wood + "_log[axis=z]";
            case 12 -> wood + "_wood";
            default -> wood + "_log[axis=y]";
         };
      }

      private static String leaves(int data, boolean leaves2) {
         String wood = leaves2 ? (data & 1) == 0 ? "acacia" : "dark_oak" : PLANK[data & 3];
         return wood + "_leaves";
      }

      private static String stoneSlab(int data) {
         String type = switch (data & 7) {
            case 1 -> "sandstone_slab"; case 3 -> "cobblestone_slab"; case 4 -> "brick_slab";
            case 5 -> "stone_brick_slab"; case 6 -> "nether_brick_slab"; case 7 -> "quartz_slab";
            default -> "smooth_stone_slab";
         };
         return type + slabHalf(data);
      }

      private static String slabHalf(int data) {
         return (data & 8) != 0 ? "[type=top]" : "[type=bottom]";
      }

      private static String stairProps(int data) {
         String facing = switch (data & 3) { case 0 -> "east"; case 1 -> "west"; case 2 -> "south"; default -> "north"; };
         String half = (data & 4) != 0 ? "top" : "bottom";
         return "[facing=" + facing + ",half=" + half + "]";
      }
   }
}
