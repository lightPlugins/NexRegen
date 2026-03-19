package io.nexstudios.nexregen.service.listener;

import io.nexstudios.nexregen.service.manager.RegenManager;
import io.nexstudios.nexregen.service.model.BreakKey;
import io.nexstudios.serviceregistry.di.Dependencies;
import io.nexstudios.serviceregistry.di.ServiceAccessor;
import org.bukkit.GameMode;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;

@Dependencies({
    RegenManager.class
})
public final class RegenBlockBreakListener implements Listener {

  private final RegenManager regen;

  public RegenBlockBreakListener(ServiceAccessor accessor) {
    this.regen = accessor.getService(RegenManager.class);
  }

  @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
  public void onBreakPre(BlockBreakEvent event) {
    Player player = event.getPlayer();
    Block block = event.getBlock();

    if (player.getGameMode().equals(GameMode.CREATIVE)) return;
    if (regen.isInternalBreak(block)) return;

    // We always cancel vanilla breaking for managed blocks;
    // if not managed, do nothing.
    if (regen.matchEntry(block).isEmpty()) return;

    event.setCancelled(true);
    event.setDropItems(false);
    event.setExpToDrop(0);

    // If it's fake-locked, restore the fake view to prevent client desync.
    if (regen.isFakeLocked(player, block)) {
      regen.fakeGetView(player, block).ifPresent(view ->
          org.bukkit.Bukkit.getScheduler().runTask(regen.plugin(), () -> {
            if (!player.isOnline()) return;
            player.sendBlockChange(block.getLocation(), view);
          })
      );
    }
  }

  @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
  public void onBreak(BlockBreakEvent event) {
    Player player = event.getPlayer();
    Block block = event.getBlock();

    if (player.getGameMode().equals(GameMode.CREATIVE)) return;
    if (regen.isInternalBreak(block)) return;

    // Always cancel vanilla behavior for managed blocks.
    if (regen.matchEntry(block).isEmpty()) return;

    event.setCancelled(true);
    event.setDropItems(false);
    event.setExpToDrop(0);

    BreakKey key = BreakKey.fromBreak(player.isSneaking());
    regen.attemptBreak(player, block, key);
  }
}