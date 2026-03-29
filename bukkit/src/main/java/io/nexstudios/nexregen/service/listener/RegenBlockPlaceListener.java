package io.nexstudios.nexregen.service.listener;

import io.nexstudios.framework.paper.services.ServiceListener;
import io.nexstudios.nexregen.service.manager.RegenManager;
import io.nexstudios.serviceregistry.di.Dependencies;
import io.nexstudios.serviceregistry.di.ServiceAccessor;
import org.bukkit.GameMode;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.block.BlockPlaceEvent;

@Dependencies({
    RegenManager.class
})
public final class RegenBlockPlaceListener implements ServiceListener {

  private final RegenManager regen;

  public RegenBlockPlaceListener(ServiceAccessor accessor) {
    this.regen = accessor.getService(RegenManager.class);
  }

  @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
  public void onBlockPlace(BlockPlaceEvent event) {
    Player player = event.getPlayer();
    
    if (player.getGameMode().equals(GameMode.CREATIVE)) {
      return;
    }
    
    event.setCancelled(true);
  }
}

