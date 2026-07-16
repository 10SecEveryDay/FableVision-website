package com.fablevision.client.builder;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.Container;
import net.minecraft.world.Containers;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/**
 * The "builder": places one schematic gradually, a few blocks per server tick, paying
 * for every block from the designated chest. Not an entity — just a paced task.
 *
 * Safety rules: never breaks existing blocks (only replaceable spots), never creates
 * materials — every block is paid from the chest (water/lava consume a filled bucket
 * and the EMPTY bucket goes back, exactly like using it by hand), pauses cleanly when
 * materials/chunks/chest are missing, and is idempotent — positions that already match
 * the schematic are skipped for free, so resuming after a crash, world exit, or restock
 * can never double-place or corrupt anything.
 */
public final class BuildTask {
   public enum State { RUNNING, WAITING_MATERIALS, WAITING_CHUNKS, WAITING_CHEST, PAUSED, DONE, CANCELLED }

   public final Schematic schematic;
   public final ResourceKey<Level> dimension;
   public final BlockPos origin;
   /** Where materials are paid from. Mutable so /build chest can retarget a restored or
    *  stuck build (may be null after a restore — the task then waits for a chest). */
   public volatile BlockPos chestPos;

   public volatile State state = State.RUNNING;
   public int index; // next placement to attempt
   public int placed;
   public int skippedOccupied;
   public int skippedUnobtainable;
   public String waitingFor = ""; // item name when WAITING_MATERIALS
   public int waitingNeed;        // how many of that item this build still needs

   private int recheckCooldown = 0; // ticks until the next auto-resume check

   public BuildTask(Schematic schematic, ResourceKey<Level> dimension, BlockPos origin, BlockPos chestPos, int startIndex,
                    int placed, int skippedOccupied, int skippedUnobtainable) {
      this.schematic = schematic;
      this.dimension = dimension;
      this.origin = origin;
      this.chestPos = chestPos;
      this.index = Math.max(0, Math.min(startIndex, schematic.placements.size()));
      this.placed = placed;
      this.skippedOccupied = skippedOccupied;
      this.skippedUnobtainable = skippedUnobtainable;
   }

   public int total() {
      return schematic.placements.size();
   }

   public int percent() {
      return total() == 0 ? 100 : (index * 100) / total();
   }

   public boolean isActive() {
      return state != State.DONE && state != State.CANCELLED;
   }

   /** Points this build at a (new) material chest and rechecks immediately — used by
    *  /build chest so restocking a different chest actually reaches an in-flight build. */
   public void retargetChest(BlockPos pos) {
      this.chestPos = pos;
      this.recheckCooldown = 0;
   }

   /**
    * One server tick of building. Runs ON the server thread. Returns a message to show
    * the player (or null). Never throws — any surprise is caught by the manager.
    */
   public String tick(ServerLevel level, int blocksPerTick) {
      if (state == State.PAUSED || !isActive()) return null;

      // No chest at all (e.g. restored from an old save without one) — wait until one is set.
      if (chestPos == null) {
         if (state != State.WAITING_CHEST) {
            state = State.WAITING_CHEST;
            return "§cNo material chest set — aim at one and run §f/build chest§c. It resumes automatically.";
         }
         return null;
      }

      // Auto-resume checks for the waiting states, throttled to every 2 seconds.
      if (state == State.WAITING_MATERIALS || state == State.WAITING_CHUNKS || state == State.WAITING_CHEST) {
         if (recheckCooldown-- > 0) return null;
         recheckCooldown = 40;
         if (state == State.WAITING_CHUNKS) {
            if (!areaLoaded(level)) return null;
         } else { // chest / materials — both need the chest back and, for materials, the item present
            Container chest = chest(level);
            if (chest == null) { state = State.WAITING_CHEST; return null; }
            if (state == State.WAITING_MATERIALS && index < total()) {
               Item needed = Schematic.placementItem(schematic.placements.get(index).state());
               if (findSlot(chest, needed) < 0) return null;
            }
         }
         state = State.RUNNING;
         return "§aResuming build — §7" + percent() + "% done.";
      }

      int budget = Math.max(1, blocksPerTick);
      // Free skips (already-correct / occupied / no-item spots) don't spend budget, so a resume
      // of a mostly-built schematic could otherwise scan hundreds of thousands of entries in ONE
      // tick and freeze the server. Cap total work per tick; progress just continues next tick.
      int scan = 20000;
      while (budget > 0 && scan-- > 0) {
         if (index >= total()) {
            state = State.DONE;
            return doneSummary();
         }

         Schematic.Placement p = schematic.placements.get(index);
         BlockPos worldPos = origin.offset(p.pos().getX(), p.pos().getY(), p.pos().getZ());

         if (!level.isInWorldBounds(worldPos)) {
            skippedOccupied++;
            index++;
            continue;
         }
         if (!level.isLoaded(worldPos) || !level.isLoaded(chestPos)) {
            state = State.WAITING_CHUNKS;
            recheckCooldown = 40;
            return "§eBuild paused — the build area isn't loaded. Move closer; it resumes automatically.";
         }

         BlockState current = level.getBlockState(worldPos);
         if (current == p.state()) { // already correct (e.g. resumed build) — free skip
            index++;
            continue;
         }
         if (!current.canBeReplaced()) { // never destroy existing blocks
            skippedOccupied++;
            index++;
            continue;
         }

         // The item you'd gather for this block — water/lava cost a filled bucket.
         Item needed = Schematic.placementItem(p.state());
         if (needed == Items.AIR) { // no obtainable item for this block (e.g. fire) — skip
            skippedUnobtainable++;
            index++;
            continue;
         }

         Container chest = chest(level);
         if (chest == null) {
            state = State.WAITING_CHEST;
            recheckCooldown = 40;
            return "§cThe material chest is gone! Put a chest back at "
                  + chestPos.getX() + " " + chestPos.getY() + " " + chestPos.getZ()
                  + " §c(or aim at a new one and §f/build chest§c). It resumes automatically.";
         }

         int slot = findSlot(chest, needed);
         if (slot < 0) {
            state = State.WAITING_MATERIALS;
            waitingFor = itemName(needed);
            waitingNeed = countRemaining(needed);
            recheckCooldown = 40;
            return "§eOut of §f" + waitingFor + "§e — needs §f" + waitingNeed + "§e more (" + percent()
                  + "% done). Add them to the chest; it continues by itself.";
         }

         // Pay first, then place. removeItem shrinks the stack in the chest by exactly 1.
         chest.removeItem(slot, 1);
         if (needed == Items.WATER_BUCKET || needed == Items.LAVA_BUCKET) {
            giveBack(level, chest, new ItemStack(Items.BUCKET)); // liquid used, bucket returned
         }
         chest.setChanged();
         // Place the EXACT schematic state with shape updates suppressed (like a schematic
         // paste): otherwise vanilla "fixes" states mid-build — a double-chest half placed
         // before its partner snaps back to a single chest (the glitched chests bug), fences
         // and rails re-shape, and redstone fires while the contraption is half-built.
         level.setBlock(worldPos, p.state(), Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
         placed++;
         index++;
         budget--;
      }
      return null;
   }

   public boolean areaLoaded(ServerLevel level) {
      BlockPos a = origin;
      BlockPos b = origin.offset(schematic.sizeX - 1, schematic.sizeY - 1, schematic.sizeZ - 1);
      BlockPos c = chestPos;
      return level.isLoaded(a) && level.isLoaded(b) && (c == null || level.isLoaded(c));
   }

   private Container chest(ServerLevel level) {
      BlockPos p = chestPos;
      if (p == null) return null;
      // A double chest is TWO block entities — getContainer joins both halves into one
      // 54-slot container, so materials in either half are found. `true` = usable even
      // with a block or cat on the lid (the builder doesn't need to animate it open).
      BlockState state = level.getBlockState(p);
      if (state.getBlock() instanceof ChestBlock cb) {
         Container both = ChestBlock.getContainer(cb, state, level, p, true);
         if (both != null) return both;
      }
      BlockEntity be = level.getBlockEntity(p);
      return be instanceof Container c ? c : null;
   }

   private static int findSlot(Container chest, Item needed) {
      for (int i = 0; i < chest.getContainerSize(); i++) {
         ItemStack s = chest.getItem(i);
         if (!s.isEmpty() && s.getItem() == needed) return i;
      }
      return -1;
   }

   /** How many of the remaining (not yet reached) placements still need this item. */
   private int countRemaining(Item item) {
      int n = 0;
      for (int i = index; i < schematic.placements.size(); i++) {
         if (Schematic.placementItem(schematic.placements.get(i).state()) == item) n++;
      }
      return n;
   }

   /** Puts an item back into the chest — stacks it, uses an empty slot, or (chest full)
    *  drops it on top so nothing is ever lost. Used for the empty bucket after a liquid. */
   private void giveBack(ServerLevel level, Container chest, ItemStack stack) {
      for (int i = 0; i < chest.getContainerSize(); i++) {
         ItemStack s = chest.getItem(i);
         if (!s.isEmpty() && s.getItem() == stack.getItem() && s.getCount() < s.getMaxStackSize()) {
            s.grow(stack.getCount());
            return;
         }
      }
      for (int i = 0; i < chest.getContainerSize(); i++) {
         if (chest.getItem(i).isEmpty()) {
            chest.setItem(i, stack);
            return;
         }
      }
      BlockPos c = chestPos;
      if (c != null) {
         Containers.dropItemStack(level, c.getX() + 0.5, c.getY() + 1.0, c.getZ() + 0.5, stack);
      }
   }

   public String doneSummary() {
      String extra = "";
      if (skippedOccupied > 0) extra += " §7(" + skippedOccupied + " spots were blocked by existing blocks and skipped)";
      if (skippedUnobtainable > 0) extra += " §7(" + skippedUnobtainable + " blocks have no item and were skipped)";
      return "§aBuild complete! §f" + placed + "§a blocks placed." + extra;
   }

   public static String itemName(Item item) {
      try {
         return BuiltInRegistries.ITEM.getKey(item).getPath().replace('_', ' ');
      } catch (Throwable t) {
         return "the next material";
      }
   }
}
