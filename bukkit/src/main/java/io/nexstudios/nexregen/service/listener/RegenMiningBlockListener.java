package io.nexstudios.nexregen.service.listener;

import io.nexstudios.framework.paper.services.ServiceListener;
import io.nexstudios.framework.paper.services.plugin.PaperPluginService;
import io.nexstudios.nexregen.service.RegenManager;
import io.nexstudios.nexregen.service.model.RegenEntry;
import io.nexstudios.serviceregistry.di.Dependencies;
import io.nexstudios.serviceregistry.di.ServiceAccessor;
import org.bukkit.GameMode;
import org.bukkit.NamespacedKey;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.block.BlockDamageAbortEvent;
import org.bukkit.event.block.BlockDamageEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.plugin.Plugin;

import java.util.Optional;

@Dependencies({
    RegenManager.class
})
public final class RegenMiningBlockListener implements ServiceListener {

  private final NamespacedKey miningBlockModifierKey;
  private final RegenManager regen;

  public RegenMiningBlockListener(ServiceAccessor accessor) {
    this.regen = accessor.getService(RegenManager.class);

    Plugin plugin = accessor.getService(PaperPluginService.class).plugin();
    this.miningBlockModifierKey = new NamespacedKey(plugin, "mining_block");
  }

  @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
  public void onDamage(BlockDamageEvent event) {
    Block block = event.getBlock();
    Player player = event.getPlayer();

    if (player.getGameMode().equals(GameMode.CREATIVE)) { return; }

    boolean allowed = true;

    // Never allow damaging fake-locked blocks
    if (regen.isFakeLocked(player, block)) {
      allowed = false;
    } else {
      Optional<RegenEntry> match = regen.matchEntry(block);
      if (match.isEmpty()) {
        allowed = false;
      } else {
        RegenEntry entry = match.get();

        boolean globalOk = regen.conditions().evaluateAll(entry.globalConditions(), block, player, "nexregen-global");
        if (!globalOk) {
          allowed = false;
        } else {
          boolean breakOk = regen.conditions().evaluateAll(entry.breakConditions(), block, player, "nexregen-break");
          if (!breakOk) {
            allowed = false;
          }
        }
      }
    }

    if (!allowed) {
      event.setCancelled(true);
      applyMiningBlock(player);
      return;
    }

    clearMiningBlock(player);
  }

  @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = false)
  public void onDamageAbort(BlockDamageAbortEvent event) {
    clearMiningBlock(event.getPlayer());
  }

  @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = false)
  public void onQuit(PlayerQuitEvent event) {
    clearMiningBlock(event.getPlayer());
  }

  @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = false)
  public void onTeleport(PlayerTeleportEvent event) {
    clearMiningBlock(event.getPlayer());
  }

  private void applyMiningBlock(Player player) {
    if (player == null) return;

    setZeroMultiplier(player, Attribute.BLOCK_BREAK_SPEED);
    setZeroMultiplier(player, Attribute.MINING_EFFICIENCY);
    setZeroMultiplier(player, Attribute.SUBMERGED_MINING_SPEED);
  }

  private void clearMiningBlock(Player player) {
    if (player == null) return;

    removeByKey(player, Attribute.BLOCK_BREAK_SPEED);
    removeByKey(player, Attribute.MINING_EFFICIENCY);
    removeByKey(player, Attribute.SUBMERGED_MINING_SPEED);
  }

  private void setZeroMultiplier(Player player, Attribute attribute) {
    AttributeInstance inst = player.getAttribute(attribute);
    if (inst == null) return;

    inst.getModifiers().stream()
        .filter(m -> miningBlockModifierKey.equals(m.getKey()))
        .forEach(inst::removeModifier);

    AttributeModifier mod = new AttributeModifier(
        miningBlockModifierKey,
        -1.0D,
        AttributeModifier.Operation.MULTIPLY_SCALAR_1
    );
    inst.addModifier(mod);
  }

  private void removeByKey(Player player, Attribute attribute) {
    AttributeInstance inst = player.getAttribute(attribute);
    if (inst == null) return;

    inst.getModifiers().stream()
        .filter(m -> miningBlockModifierKey.equals(m.getKey()))
        .forEach(inst::removeModifier);
  }
}