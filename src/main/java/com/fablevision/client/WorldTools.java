package com.fablevision.client;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.storage.LevelStorageSource.LevelStorageAccess;

public final class WorldTools {
   private WorldTools() {
   }

   public static String duplicate(LevelStorageAccess access) throws IOException {
      Path source = access.getLevelDirectory().path();
      Path savesDir = source.getParent();
      String id = access.getLevelId();
      String newId = id + " copy";
      int n = 2;

      while (Files.exists(savesDir.resolve(newId))) {
         newId = id + " copy " + n++;
      }

      Path target = savesDir.resolve(newId);

      try (Stream<Path> stream = Files.walk(source)) {
         for (Path from : (Iterable<Path>)stream::iterator) {
            if (!from.getFileName().toString().equals("session.lock")) {
               Path to = target.resolve(source.relativize(from).toString());
               if (Files.isDirectory(from)) {
                  Files.createDirectories(to);
               } else {
                  Files.createDirectories(to.getParent());
                  Files.copy(from, to);
               }
            }
         }
      }

      Path levelDat = target.resolve("level.dat");
      if (Files.exists(levelDat)) {
         CompoundTag root = NbtIo.readCompressed(levelDat, NbtAccounter.unlimitedHeap());
         CompoundTag data = root.getCompound("Data").orElse(null);
         if (data != null) {
            String display = data.getString("LevelName").orElse(id);
            data.putString("LevelName", display + " (Copy)");
            NbtIo.writeCompressed(root, levelDat);
         }
      }

      return newId;
   }

   public static GameType readGameType(LevelStorageAccess access) {
      try {
         CompoundTag root = NbtIo.readCompressed(access.getLevelDirectory().dataFile(), NbtAccounter.unlimitedHeap());
         CompoundTag data = root.getCompound("Data").orElse(null);
         if (data != null) {
            return GameType.byId(data.getInt("GameType").orElse(0));
         }
      } catch (Exception e) {
         FableVisionClient.LOGGER.warn("Could not read gamemode from level.dat", e);
      }

      return GameType.SURVIVAL;
   }

   public static void writeGameType(LevelStorageAccess access, GameType type) throws IOException {
      Path levelDat = access.getLevelDirectory().dataFile();
      CompoundTag root = NbtIo.readCompressed(levelDat, NbtAccounter.unlimitedHeap());
      CompoundTag data = root.getCompound("Data").orElseThrow(() -> new IOException("level.dat has no Data tag"));
      data.putInt("GameType", type.getId());
      data.getCompound("Player").ifPresent(player -> player.putInt("playerGameType", type.getId()));
      NbtIo.writeCompressed(root, levelDat);
   }
}
