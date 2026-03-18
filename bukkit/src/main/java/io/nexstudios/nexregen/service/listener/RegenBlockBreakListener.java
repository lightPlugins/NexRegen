package io.nexstudios.nexregen.service.listener;

import io.nexstudios.nexregen.service.manager.RegenManager;
import io.nexstudios.nexregen.service.model.RegenEntry;
import io.nexstudios.nexregen.service.model.RegenSettings;
import io.nexstudios.nexregen.util.AffectedBlockCalculator;
import io.nexstudios.nexregen.util.BlockDataSpec;
import io.nexstudios.nexregen.util.RegenTimeParser;
import io.nexstudios.serviceregistry.di.Dependencies;
import io.nexstudios.serviceregistry.di.ServiceAccessor;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.inventory.ItemStack;

import java.util.*;

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

    if (event.getPlayer().getGameMode().equals(GameMode.CREATIVE)) { return; }

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

    if (player.getGameMode().equals(GameMode.CREATIVE)) { return; }

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

    AffectedBlockCalculator.Affected affected = AffectedBlockCalculator.computeAffected(block);
    RegenSettings settings = entry.settings();

    List<Block> viewOnlyBlocks = affected.blocksWithoutBreakEvent() != null ? affected.blocksWithoutBreakEvent() : List.of();

    List<Block> allAffectedBlocks;
    if (viewOnlyBlocks.isEmpty()) {
      allAffectedBlocks = affected.blocksWithBreakEvent();
    } else {
      ArrayList<Block> tmp = new ArrayList<>(affected.blocksWithBreakEvent().size() + viewOnlyBlocks.size());
      tmp.addAll(affected.blocksWithBreakEvent());
      for (Block b : viewOnlyBlocks) {
        if (b == null) continue;
        if (!AffectedBlockCalculator.containsSameBlock(tmp, b)) tmp.add(b);
      }
      allAffectedBlocks = List.copyOf(tmp);
    }

    // Fire synthetic BlockBreakEvents (including the clicked block),
    // but skip the bottom block if replace-only-bottom is enabled.
    Set<Block> syntheticBlocks = new HashSet<>(affected.blocksWithBreakEvent());
    syntheticBlocks.add(block);

    regen.withInternalBreak(syntheticBlocks, () -> {
      for (Block b : syntheticBlocks) {
        if (settings.replaceOnlyBottom()
            && Objects.equals(b, affected.bottomMost())) {
          continue;
        }

        @SuppressWarnings("UnstableApiUsage")
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
        for (Block b : affected.blocksWithBreakEvent()) {
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
    if (affected.columnBlocks() != null && !affected.columnBlocks().isEmpty()) {
      replacementTargets = settings.replaceOnlyBottom()
          ? List.of(affected.bottomMost())
          : List.copyOf(affected.columnBlocks());
    } else {
      replacementTargets = List.of(block);
    }

    BlockData air = Bukkit.createBlockData("minecraft:air");

    BlockData replacementData = BlockDataSpec.toBlockDataWithoutAge(entry.replacementBlockDataSpec());
    BlockDataSpec.applyAgeIfPossible(
        replacementData,
        entry.replacementAge().isPresent() ? Optional.of(entry.replacementAge().getAsInt()) : Optional.empty()
    );

    BlockData finalData = BlockDataSpec.toBlockDataWithoutAge(entry.finalBlockDataSpec());
    BlockDataSpec.applyAgeIfPossible(
        finalData,
        entry.finalAge().isPresent() ? Optional.of(entry.finalAge().getAsInt()) : Optional.empty()
    );

    regen.fakeLock(player, allAffectedBlocks);

    // Snapshot originals BEFORE faking anything
    for (Block b : allAffectedBlocks) {
      BlockData original = b.getBlockData().clone();
      regen.storeOriginalIfAbsent(player, b, original);
    }

    // Apply fake immediately
    for (Block b : allAffectedBlocks) {
      regen.fakeSetView(player, b, air);
      player.sendBlockChange(b.getLocation(), air);
    }
    for (Block b : replacementTargets) {
      regen.fakeSetView(player, b, replacementData);
      player.sendBlockChange(b.getLocation(), replacementData);
    }

    // Re-assert clicked block next tick
    Bukkit.getScheduler().runTask(regen.plugin(), () -> {
      regen.fakeGetView(player, block).ifPresent(view -> player.sendBlockChange(block.getLocation(), view));
    });

    long delayTicks = RegenTimeParser.parseToTicks(entry.regenTimeSpec());
    Bukkit.getScheduler().runTaskLater(regen.plugin(), () -> {
      try {
        for (Block b : allAffectedBlocks) {
          BlockData restore = regen.getOriginal(player, b).orElse(finalData);
          player.sendBlockChange(b.getLocation(), restore);
        }
      } finally {
        regen.fakeUnlock(player, allAffectedBlocks);
      }
    }, delayTicks);
  }

  private static void dropNaturally(Player player, Block at, Collection<ItemStack> drops) {
    if (drops == null || drops.isEmpty()) return;
    for (ItemStack is : drops) {
      if (is == null || is.getType().isAir() || is.getAmount() <= 0) continue;
      player.getWorld().dropItemNaturally(at.getLocation().add(0.5, 0.5, 0.5), is);
    }
  }
}