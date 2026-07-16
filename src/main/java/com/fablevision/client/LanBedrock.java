package com.fablevision.client;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Enumeration;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.network.chat.Component;
import net.minecraft.util.HttpUtil;

/**
 * Opens the current singleplayer world to LAN and reports how Bedrock players join
 * through Geyser. Detects whether Geyser-Fabric is actually loaded and flags it if not.
 */
public final class LanBedrock {
   /** Geyser's default Bedrock UDP port (config/Geyser-Fabric/config.yml -> port). */
   public static final int GEYSER_BEDROCK_PORT = 19132;

   private LanBedrock() {
   }

   public static boolean geyserInstalled() {
      FabricLoader loader = FabricLoader.getInstance();
      return loader.isModLoaded("geyser-fabric") || loader.isModLoaded("geyser");
   }

   /** True when connected to a multiplayer server (not hosting our own world). */
   public static boolean onRemoteServer(Minecraft mc) {
      return mc.level != null && !mc.isLocalServer();
   }

   /** Opens the world to LAN (if it isn't already) and prints join info for Java + Bedrock. */
   public static void openToLan(Minecraft mc) {
      if (onRemoteServer(mc)) {
         // LAN sharing is deliberately disabled on servers. Explain once, then stay quiet.
         if (!FableVisionConfig.lanServerNoticeShown) {
            FableVisionConfig.lanServerNoticeShown = true;
            FableVisionConfig.save();
            say(mc, Component.literal("LAN sharing only works in your own single-player world.").withStyle(ChatFormatting.GOLD));
            say(
               mc,
               Component.literal("It's disabled on servers to protect your connection and prevent exposing your network. ")
                  .withStyle(ChatFormatting.GRAY)
                  .append(Component.literal("We care about ur safety o7").withStyle(ChatFormatting.AQUA))
            );
         } else {
            say(mc, Component.literal("LAN sharing is disabled on servers.").withStyle(ChatFormatting.DARK_GRAY));
         }
         return;
      }

      if (!mc.hasSingleplayerServer() || mc.getSingleplayerServer() == null) {
         say(mc, Component.literal("Open a singleplayer world first, then use LAN + Bedrock.").withStyle(ChatFormatting.RED));
         return;
      }

      IntegratedServer server = mc.getSingleplayerServer();
      boolean freshlyOpened = false;
      if (!server.isPublished()) {
         int port = HttpUtil.getAvailablePort();
         boolean ok = server.publishServer(server.getWorldData().getGameType(), server.getWorldData().isAllowCommands(), port);
         if (!ok) {
            say(mc, Component.literal("Couldn't open the world to LAN (port " + port + " refused).").withStyle(ChatFormatting.RED));
            return;
         }
         freshlyOpened = true;
      }

      String ip = lanIp();
      say(mc, Component.literal(freshlyOpened ? "World opened to LAN." : "World is already open to LAN.").withStyle(ChatFormatting.GREEN));
      say(
         mc,
         Component.literal("Java players (same Wi-Fi): ")
            .withStyle(ChatFormatting.GRAY)
            .append(Component.literal(ip + ":" + server.getPort()).withStyle(ChatFormatting.YELLOW))
      );
      if (geyserInstalled()) {
         say(
            mc,
            Component.literal("Geyser detected - Bedrock players (same Wi-Fi): ")
               .withStyle(ChatFormatting.GRAY)
               .append(Component.literal(ip + ":" + GEYSER_BEDROCK_PORT).withStyle(ChatFormatting.AQUA))
               .append(Component.literal("  (Add Server in Bedrock's Servers tab)").withStyle(ChatFormatting.DARK_GRAY))
         );
      } else {
         say(mc, Component.literal("Geyser is NOT loaded - Bedrock players can't join!").withStyle(ChatFormatting.RED));
         say(
            mc,
            Component.literal("Fix: in your mods folder, rename Geyser-Fabric-....jar.disabled back to .jar and restart the game.")
               .withStyle(ChatFormatting.GRAY)
         );
      }
   }

   private static void say(Minecraft mc, Component msg) {
      if (mc.player != null) {
         mc.player.sendSystemMessage(Component.literal("[FableVision] ").withStyle(ChatFormatting.BLUE).append(msg));
      }
   }

   /** Best-effort LAN IPv4 of this PC (what friends on the same network type in). */
   private static String lanIp() {
      try {
         Enumeration<NetworkInterface> ifaces = NetworkInterface.getNetworkInterfaces();
         while (ifaces.hasMoreElements()) {
            NetworkInterface iface = ifaces.nextElement();
            if (iface.isUp() && !iface.isLoopback() && !iface.isVirtual()) {
               Enumeration<InetAddress> addrs = iface.getInetAddresses();
               while (addrs.hasMoreElements()) {
                  InetAddress a = addrs.nextElement();
                  if (a instanceof Inet4Address && a.isSiteLocalAddress()) {
                     return a.getHostAddress();
                  }
               }
            }
         }
      } catch (Exception ignored) {
      }
      return "<your PC's IP>";
   }
}
