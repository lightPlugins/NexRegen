package io.nexstudios.nexregen.service.listener;

import io.nexstudios.framework.paper.services.ServiceListener;
import io.nexstudios.nexregen.service.manager.RegenManager;
import io.nexstudios.nexregen.service.model.BreakKey;
import io.nexstudios.nexregen.service.model.RegenEntry;
import io.nexstudios.serviceregistry.di.Dependencies;
import io.nexstudios.serviceregistry.di.ServiceAccessor;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;

import java.util.List;
import java.util.Optional;

@Dependencies({
    RegenManager.class
})
public final class RegenBlockInteractListener implements ServiceListener {

  private final RegenManager regen;

  public RegenBlockInteractListener(ServiceAccessor accessor) {
    this.regen = accessor.getService(RegenManager.class);
  }

  @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
  public void onInteract(PlayerInteractEvent event) {
    Player player = event.getPlayer();
    if (player.getGameMode().equals(GameMode.CREATIVE)) return;

    if (event.getAction() != Action.RIGHT_CLICK_BLOCK) return;

    Block clicked = event.getClickedBlock();
    if (clicked == null) return;

    // ignore on interactable (so chests etc still work unless they are regen-managed)
    List<Material> whitelist = List.of(
        Material.CHEST,
        Material.TRAPPED_CHEST,
        Material.BARREL,
        Material.FURNACE,
        Material.BLAST_FURNACE,
        Material.SMOKER,
        Material.CRAFTING_TABLE,
        Material.ANVIL,
        Material.CHIPPED_ANVIL,
        Material.DAMAGED_ANVIL,
        Material.ENCHANTING_TABLE,
        Material.GRINDSTONE,
        Material.CARTOGRAPHY_TABLE,
        Material.LOOM,
        Material.STONECUTTER,
        Material.BELL,
        Material.JUKEBOX,
        Material.COMPOSTER
    );

    if (whitelist.contains(clicked.getType())) {
      // no-op (kept for clarity)
      return;
    }

    Optional<RegenEntry> match = regen.matchEntry(clicked);
    if (match.isEmpty()) {
      return;
    }

    // If the block is regen-managed, we handle right click ourselves.
    RegenEntry entry = match.get();

    BreakKey key = BreakKey.fromInteract(event);
    if (key == null) return;

    // If right-click is not allowed, deny interaction completely.
    if (!regen.isBreakKeyAllowed(entry, key)) {
      regen.denyInteract(player, event);
      return;
    }

    // If allowed, behave like breaking: trigger the same regen flow.
    regen.denyInteract(player, event);
    regen.attemptBreak(player, clicked, key);

  }
}