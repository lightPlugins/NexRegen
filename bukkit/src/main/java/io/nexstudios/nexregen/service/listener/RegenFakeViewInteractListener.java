package io.nexstudios.nexregen.service.listener;

import io.nexstudios.framework.paper.services.ServiceListener;
import io.nexstudios.nexregen.service.RegenManager;
import io.nexstudios.nexregen.service.util.BlockKey;
import io.nexstudios.serviceregistry.di.Dependencies;
import io.nexstudios.serviceregistry.di.ServiceAccessor;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;

import java.util.Map;
import java.util.UUID;

@Dependencies({
    RegenManager.class
})
public final class RegenFakeViewInteractListener implements ServiceListener {

  private final RegenManager regen;

  public RegenFakeViewInteractListener(ServiceAccessor accessor) {
    this.regen = accessor.getService(RegenManager.class);
  }

  @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
  public void onInteract(PlayerInteractEvent event) {
    if (event.getPlayer().getGameMode().equals(GameMode.CREATIVE)) { return; }
    if (event.getAction() != Action.RIGHT_CLICK_BLOCK) return;

    Block clicked = event.getClickedBlock();
    if (clicked == null) return;

    Player player = event.getPlayer();

    Map<BlockKey, BlockData> views = regen.fakeViews(player);
    if (views.isEmpty()) return;

    UUID wid = clicked.getWorld().getUID();
    int x = clicked.getX();
    int y = clicked.getY();
    int z = clicked.getZ();

    boolean hasFakeAtExactBlock = views.containsKey(new BlockKey(wid, x, y, z));
    boolean hasFakeInSameColumn = views.keySet().stream().anyMatch(k -> k.worldId().equals(wid) && k.x() == x && k.z() == z);

    if (!hasFakeAtExactBlock && !hasFakeInSameColumn) return;

    event.setCancelled(true);
    event.setUseInteractedBlock(Event.Result.DENY);
    event.setUseItemInHand(Event.Result.DENY);

    Bukkit.getScheduler().runTask(regen.plugin(), () -> {
      if (!player.isOnline()) return;

      World w = Bukkit.getWorld(wid);
      if (w == null) return;

      if (hasFakeInSameColumn) {
        for (Map.Entry<BlockKey, BlockData> e : views.entrySet()) {
          BlockKey k = e.getKey();
          if (!k.worldId().equals(wid) || k.x() != x || k.z() != z) continue;
          player.sendBlockChange(new org.bukkit.Location(w, k.x(), k.y(), k.z()), e.getValue());
        }
      } else {
        BlockData data = views.get(new BlockKey(wid, x, y, z));
        if (data != null) {
          player.sendBlockChange(clicked.getLocation(), data);
        }
      }
    });
  }
}