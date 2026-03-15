package io.nexstudios.nexregen.service.listener;

import io.nexstudios.nexlogic.common.services.logging.LoggerService;
import io.nexstudios.nexregen.service.RegenConfigLoader;
import io.nexstudios.nexregen.service.RegenManager;
import io.nexstudios.nexregen.service.model.RegenEntry;
import io.nexstudios.nexregen.service.model.RegenSettings;
import io.nexstudios.serviceregistry.di.Dependencies;
import io.nexstudios.serviceregistry.di.ServiceAccessor;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.block.Block;
import org.bukkit.block.data.Bisected;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Dependencies({
    RegenManager.class,
    LoggerService.class,
})
public final class RegenBlockBreakListener implements Listener {

  private final RegenManager regen;
  private final LoggerService logger;

  public RegenBlockBreakListener(ServiceAccessor accessor) {
    this.logger = accessor.getService(LoggerService.class);
    this.regen = accessor.getService(RegenManager.class);
  }

  @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
  public void onBreak(BlockBreakEvent event) {
    Block block = event.getBlock();
    Player player = event.getPlayer();

    if (regen.isInternalBreak(block)) {
      return;
    }

    Optional<RegenEntry> match = regen.matchEntry(block);
    if (match.isEmpty()) return;

    RegenEntry entry = match.get();

    boolean globalOk = regen.conditions().evaluateAll(entry.globalConditions(), block, player, "nexregen-global");
    if (!globalOk)  {
      logger.logger().info("Global condition failed");
      return;
    }

    boolean breakOk = regen.conditions().evaluateAll(entry.breakConditions(), block, player, "nexregen-break");
    if (!breakOk) {
      logger.logger().info("Break condition failed");
      return;
    }

    // We control the break now.
    event.setCancelled(true);

    Affected affected = computeAffected(block);

    // Synthetic BlockBreakEvent for blocks that should be visible as broken to other plugins
    regen.withInternalBreak(affected.blocksWithBreakEvent, () -> {
      for (Block b : affected.blocksWithBreakEvent) {
        BlockBreakEvent synthetic = new BlockBreakEvent(b, player);
        synthetic.setDropItems(false);
        synthetic.setExpToDrop(0);
        Bukkit.getPluginManager().callEvent(synthetic);
      }
      return null;
    });

    RegenSettings settings = entry.settings();

    // Drops / XP (manual)
    if (settings.dropItems() || settings.dropXp()) {
      ItemStack tool = player.getInventory().getItemInMainHand();

      if (settings.dropItems()) {
        for (Block b : affected.blocksWithBreakEvent) {
          dropNaturally(player, b, b.getDrops(tool, player));
        }
      }

      if (settings.dropXp()) {
        int exp = event.getExpToDrop();
        if (exp > 0) {
          player.giveExp(exp);
        }
      }
    }

    // Apply replacement to base block and schedule regen (includes cactus-top decoration restore)
    regen.applyReplacementAndScheduleRegen(block, entry, affected.extraRestoresOnRegen);

    // Remove stacked blocks above base for cactus/sugar cane/bamboo
    if (affected.blocksToRemoveNow.size() > 1) {
      for (int i = 1; i < affected.blocksToRemoveNow.size(); i++) {
        regen.setToAir(affected.blocksToRemoveNow.get(i), settings);
      }
    }

    // Remove cactus top decoration now WITHOUT break event (and without drops)
    if (affected.extraBlocksToRemoveNow != null) {
      regen.setToAir(affected.extraBlocksToRemoveNow, settings);
    }

    // Remove double-height other half
    if (affected.doubleHeightOtherHalf != null) {
      regen.setToAir(affected.doubleHeightOtherHalf, settings);
    }
  }

  private static void dropNaturally(Player player, Block at, Collection<ItemStack> drops) {
    if (drops == null || drops.isEmpty()) return;
    for (ItemStack is : drops) {
      if (is == null || is.getType() == Material.AIR || is.getAmount() <= 0) continue;
      player.getWorld().dropItemNaturally(at.getLocation().add(0.5, 0.5, 0.5), is);
    }
  }

  private static Affected computeAffected(Block base) {
    // Double height handling
    if (base.getBlockData() instanceof Bisected bisected) {
      Block other = bisected.getHalf() == Bisected.Half.BOTTOM
          ? base.getRelative(0, 1, 0)
          : base.getRelative(0, -1, 0);

      List<Block> withBreak = List.of(base, other);
      return new Affected(List.of(base), withBreak, List.of(), null, other);
    }

    Material type = base.getType();
    if (type == Material.CACTUS || type == Material.SUGAR_CANE || type == Material.BAMBOO) {
      List<Block> column = new ArrayList<>();
      column.add(base);

      Block cursor = base.getRelative(0, 1, 0);
      while (cursor.getType() == type) {
        column.add(cursor);
        cursor = cursor.getRelative(0, 1, 0);
      }

      List<Block> withBreakEvent = List.copyOf(column);
      List<RegenManager.RestoreAction> extraRestores = new ArrayList<>();
      Block extraRemoveNow = null;

      if (type == Material.CACTUS) {
        Block decoration = cursor; // first non-cactus above the top cactus
        if (isCactusTopDecoration(decoration.getType())) {
          BlockData data = decoration.getBlockData();
          extraRestores.add(new RegenManager.RestoreAction(
              decoration.getWorld().getUID(),
              decoration.getX(),
              decoration.getY(),
              decoration.getZ(),
              data
          ));
          extraRemoveNow = decoration;
        }
      }

      return new Affected(column, withBreakEvent, extraRestores, extraRemoveNow, null);
    }

    return new Affected(List.of(base), List.of(base), List.of(), null, null);
  }

  private static boolean isCactusTopDecoration(Material mat) {
    if (mat == Material.AIR) return false;
    return Tag.FLOWERS.isTagged(mat);
  }

  private record Affected(
      List<Block> blocksToRemoveNow,
      List<Block> blocksWithBreakEvent,
      List<RegenManager.RestoreAction> extraRestoresOnRegen,
      Block extraBlocksToRemoveNow,
      Block doubleHeightOtherHalf
  ) {
  }
}