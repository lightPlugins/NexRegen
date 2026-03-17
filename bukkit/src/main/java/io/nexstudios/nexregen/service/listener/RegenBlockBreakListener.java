package io.nexstudios.nexregen.service.listener;

import io.nexstudios.framework.paper.services.plugin.PaperPluginService;
import io.nexstudios.nexlogic.common.services.logging.LoggerService;
import io.nexstudios.nexregen.service.RegenManager;
import io.nexstudios.nexregen.service.model.RegenEntry;
import io.nexstudios.nexregen.service.model.RegenSettings;
import io.nexstudios.nexregen.service.util.BlockDataSpec;
import io.nexstudios.nexregen.service.util.RegenTimeParser;
import io.nexstudios.serviceregistry.di.Dependencies;
import io.nexstudios.serviceregistry.di.ServiceAccessor;
import org.bukkit.*;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.block.Block;
import org.bukkit.block.data.Bisected;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Player;
import io.nexstudios.nexregen.service.util.BlockKey;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockDamageAbortEvent;
import org.bukkit.event.block.BlockDamageEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

import java.util.*;

@Dependencies({
    RegenManager.class,
    LoggerService.class
})
public final class RegenBlockBreakListener implements Listener {

  private final NamespacedKey miningBlockModifierKey;
  private final RegenManager regen;
  private final LoggerService logger;

  public RegenBlockBreakListener(ServiceAccessor accessor) {
    this.regen = accessor.getService(RegenManager.class);
    this.logger = accessor.getService(LoggerService.class);

    Plugin plugin = accessor.getService(PaperPluginService.class).plugin();
    this.miningBlockModifierKey = new NamespacedKey(plugin, "mining_block");
  }

  @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
  public void onDamage(BlockDamageEvent event) {
    Block block = event.getBlock();
    Player player = event.getPlayer();

    if(player.getGameMode().equals(GameMode.CREATIVE)) { return; }

    boolean allowed = true;

    // Never allow damaging fake-locked blocks (includes replacement blocks like coal_ore -> stone)
    if (regen.isFakeLocked(player, block)) {
      allowed = false;
    } else {
      // Only allow damage if the block is configured AND all conditions match
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
      logger.logger().info("Applied Attribute to player " + player.getName() + " (reason: not allowed");
      return;
    }

    clearMiningBlock(player);
  }

  @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = false)
  public void onDamageAbort(BlockDamageAbortEvent event) {
    clearMiningBlock(event.getPlayer());
    logger.logger().info("Aborted block damage for player " + event.getPlayer().getName());
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

  @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
  public void onBreakPre(BlockBreakEvent event) {

    if(event.getPlayer().getGameMode().equals(GameMode.CREATIVE)) { return; }

    if (regen.isInternalBreak(event.getBlock())) {
      return;
    }

    event.setCancelled(true);
    event.setDropItems(false);
    event.setExpToDrop(0);

    Player player = event.getPlayer();
    Block block = event.getBlock();

    if (regen.isFakeLocked(player, block)) {
      regen.fakeGetView(player, block).ifPresent(view -> Bukkit.getScheduler().runTask(regen.plugin(), () -> {
        if (!player.isOnline()) return;
        player.sendBlockChange(block.getLocation(), view);
      }));
    }
  }

  @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
  public void onBreak(BlockBreakEvent event) {
    Block block = event.getBlock();
    Player player = event.getPlayer();

    if(player.getGameMode().equals(GameMode.CREATIVE)) { return; }

    if (regen.isInternalBreak(block)) {
      return;
    }

    Optional<RegenEntry> match = regen.matchEntry(block);
    if (match.isEmpty()) return;

    if (regen.isFakeLocked(player, block)) return;

    RegenEntry entry = match.get();

    boolean globalOk = regen.conditions().evaluateAll(entry.globalConditions(), block, player, "nexregen-global");
    if (!globalOk) return;

    boolean breakOk = regen.conditions().evaluateAll(entry.breakConditions(), block, player, "nexregen-break");
    if (!breakOk) return;

    event.setCancelled(true);
    event.setDropItems(false);
    event.setExpToDrop(0);

    regen.fakeLock(player, block);

    Affected affected = computeAffected(block);
    RegenSettings settings = entry.settings();

    // Fire synthetic BlockBreakEvents (including the clicked block),
    // but skip the bottom block if replace-only-bottom is enabled.
    Set<Block> syntheticBlocks = new HashSet<>(affected.blocksWithBreakEvent);
    syntheticBlocks.add(block);

    regen.withInternalBreak(syntheticBlocks, () -> {
      for (Block b : syntheticBlocks) {
        if (settings.replaceOnlyBottom()
            && b.equals(affected.bottomMost)) {
          continue;
        }

        BlockBreakEvent synthetic = new BlockBreakEvent(b, player);
        synthetic.setDropItems(false);
        synthetic.setExpToDrop(0);
        Bukkit.getPluginManager().callEvent(synthetic);
      }
    });

    // Manual drops / XP handling
    if (settings.dropItems() || settings.dropXp()) {
      ItemStack tool = player.getInventory().getItemInMainHand();

      if (settings.dropItems()) {
        for (Block b : affected.blocksWithBreakEvent) {
          dropNaturally(player, b, b.getDrops(tool, player));
        }
      }

      if (settings.dropXp()) {
        int exp = event.getExpToDrop();
        if (exp > 0) player.giveExp(exp);
      }
    }

    // Determine which blocks should show the replacement state
    List<Block> replacementTargets;
    if (affected.columnBlocks != null && !affected.columnBlocks.isEmpty()) {
      replacementTargets = settings.replaceOnlyBottom()
          ? List.of(affected.bottomMost)
          : List.copyOf(affected.columnBlocks);
    } else {
      replacementTargets = List.of(block);
    }

    BlockData air = Bukkit.createBlockData("minecraft:air");

    BlockData replacementData = BlockDataSpec.toBlockDataWithoutAge(entry.replacementBlockDataSpec());
    BlockDataSpec.applyAgeIfPossible(
        replacementData,
        entry.replacementAge().isPresent() ? Optional.of(entry.replacementAge().getAsInt()) : Optional.empty()
    );

    // NOTE: finalData is still computed, but we will prefer original snapshot when restoring
    BlockData finalData = BlockDataSpec.toBlockDataWithoutAge(entry.finalBlockDataSpec());
    BlockDataSpec.applyAgeIfPossible(
        finalData,
        entry.finalAge().isPresent() ? Optional.of(entry.finalAge().getAsInt()) : Optional.empty()
    );

    regen.fakeLock(player, affected.blocksWithBreakEvent);

    // Snapshot originals BEFORE faking anything
    for (Block b : affected.blocksWithBreakEvent) {
      BlockData original = b.getBlockData().clone();
      regen.storeOriginalIfAbsent(player, b, original);
    }

    // Apply fake immediately (as you already do)
    for (Block b : affected.blocksWithBreakEvent) {
      regen.fakeSetView(player, b, air);
      player.sendBlockChange(b.getLocation(), air);
    }
    for (Block b : replacementTargets) {
      regen.fakeSetView(player, b, replacementData);
      player.sendBlockChange(b.getLocation(), replacementData);
    }

    // Re-assert clicked block next tick (keep your existing logic if present)
    Bukkit.getScheduler().runTask(regen.plugin(), () -> {
      regen.fakeGetView(player, block).ifPresent(view -> player.sendBlockChange(block.getLocation(), view));
    });

    long delayTicks = RegenTimeParser.parseToTicks(entry.regenTimeSpec());
    Bukkit.getScheduler().runTaskLater(regen.plugin(), () -> {
      try {
        for (Block b : affected.blocksWithBreakEvent) {
          BlockData restore = regen.getOriginal(player, b).orElse(finalData);
          player.sendBlockChange(b.getLocation(), restore);
        }
      } finally {
        regen.fakeUnlock(player, affected.blocksWithBreakEvent);
      }
    }, delayTicks);
  }

  @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
  public void onInteract(PlayerInteractEvent event) {
    if(event.getPlayer().getGameMode().equals(GameMode.CREATIVE)) { return; }
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

    // If there is any fake state at this block OR in this column, deny interaction.
    if (!hasFakeAtExactBlock && !hasFakeInSameColumn) return;

    event.setCancelled(true);
    event.setUseInteractedBlock(Event.Result.DENY);
    event.setUseItemInHand(Event.Result.DENY);

    // Re-assert fake views NEXT TICK (after server correction packets)
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
        // only exact block needs re-assert
        BlockData data = views.get(new BlockKey(wid, x, y, z));
        if (data != null) {
          player.sendBlockChange(clicked.getLocation(), data);
        }
      }
    });
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
      return new Affected(null, null, withBreak, other);
    }

    Material type = base.getType();
    if (type == Material.CACTUS || type == Material.SUGAR_CANE || type == Material.BAMBOO) {
      // Find bottom-most (connected downwards)
      Block bottom = base;
      Block cursorDown = base.getRelative(0, -1, 0);
      while (cursorDown.getType() == type) {
        bottom = cursorDown;
        cursorDown = cursorDown.getRelative(0, -1, 0);
      }

      // Collect full column upwards
      List<Block> column = new ArrayList<>();
      column.add(bottom);

      Block cursorUp = bottom.getRelative(0, 1, 0);
      while (cursorUp.getType() == type) {
        column.add(cursorUp);
        cursorUp = cursorUp.getRelative(0, 1, 0);
      }

      // SAFETY: ensure the clicked block is included (location-based)
      if (!containsSameBlock(column, base)) {
        column.add(base);
      }

      // keep stable ordering bottom -> top
      column.sort(Comparator.comparingInt(Block::getY));

      List<Block> withBreakEvent = List.copyOf(column);
      return new Affected(List.copyOf(column), bottom, withBreakEvent, null);
    }

    return new Affected(null, null, List.of(base), null);
  }

  private static boolean containsSameBlock(List<Block> blocks, Block needle) {
    for (Block b : blocks) {
      if (b.getWorld().equals(needle.getWorld())
          && b.getX() == needle.getX()
          && b.getY() == needle.getY()
          && b.getZ() == needle.getZ()) {
        return true;
      }
    }
    return false;
  }

  private record Affected(
      List<Block> columnBlocks,
      Block bottomMost,
      List<Block> blocksWithBreakEvent,
      Block doubleHeightOtherHalf
  ) {
  }
}