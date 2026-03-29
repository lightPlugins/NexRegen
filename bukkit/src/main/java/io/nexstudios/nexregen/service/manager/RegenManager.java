package io.nexstudios.nexregen.service.manager;

import io.nexstudios.nexregen.service.config.RegenConfigLoader;
import io.nexstudios.nexregen.service.config.PluginSettings;
import io.nexstudios.nexregen.service.model.BreakKey;
import io.nexstudios.nexregen.service.model.RegenEntry;
import io.nexstudios.nexregen.service.model.RegenSettings;
import io.nexstudios.nexregen.util.AffectedBlockCalculator;
import io.nexstudios.nexregen.util.BlockDataSpec;
import io.nexstudios.nexregen.util.BlockKey;
import io.nexstudios.nexregen.util.NexLogicConditionFacade;
import io.nexstudios.nexregen.util.RegenTimeParser;
import io.nexstudios.serviceregistry.di.Service;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.block.Block;
import org.bukkit.block.data.Ageable;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public final class RegenManager implements Service {

  private final JavaPlugin plugin;
  private final RegenConfigLoader loader;
  private volatile List<RegenEntry> entries;
  private final NexLogicConditionFacade conditions;
  private volatile PluginSettings settings;

  private final Map<UUID, Set<BlockKey>> fakeLockedByPlayer = new ConcurrentHashMap<>();
  private final Map<UUID, Map<BlockKey, BlockData>> fakeViewByPlayer = new ConcurrentHashMap<>();

  // original block state snapshot (per player) to restore after regen-time
  private final Map<UUID, Map<BlockKey, BlockData>> originalViewByPlayer = new ConcurrentHashMap<>();

  private final ThreadLocal<Set<BlockKey>> internalBreak = ThreadLocal.withInitial(HashSet::new);

  public record RemoveResult(int removed, int before, int after, String fileName) {}

  public RegenManager(JavaPlugin plugin, RegenConfigLoader loader, NexLogicConditionFacade conditions, PluginSettings settings) {
    this.plugin = plugin;
    this.loader = loader;
    this.entries = loader.loadAll();
    this.conditions = conditions;
    this.settings = settings;
  }

  public JavaPlugin plugin() {
    return plugin;
  }

  public NexLogicConditionFacade conditions() {
    return conditions;
  }

  public PluginSettings settings() {
    return settings;
  }

  public void updateSettings(PluginSettings newSettings) {
    this.settings = newSettings;
  }

  public void reloadEntries(List<RegenEntry> newEntries) {
    this.entries = List.copyOf(newEntries);
  }

  public RemoveResult removeRegenEntry(String blockFile, Block target) {
    Objects.requireNonNull(blockFile, "blockFile");
    Objects.requireNonNull(target, "target");

    String targetBlockSpec = "minecraft:" + target.getType().getKey().getKey();

    OptionalInt targetAge = OptionalInt.empty();
    if (target.getBlockData() instanceof Ageable ageable) {
      targetAge = OptionalInt.of(ageable.getAge());
    }

    RegenConfigLoader.RemoveResult res = loader.removeFromBlockFile(
        plugin.getDataFolder(),
        blockFile,
        targetBlockSpec,
        targetAge
    );

    if (res.removed() > 0) {
      reloadEntries(loader.loadAll());
    }

    return new RemoveResult(res.removed(), res.before(), res.after(), res.fileName());
  }

  public Optional<RegenEntry> matchEntry(Block block) {
    for (RegenEntry entry : entries) {
      if (block.getType() != entry.matchMaterial()) continue;
      if (entry.matchAge().isPresent()) {
        if (!(block.getBlockData() instanceof Ageable ageable)) continue;
        if (ageable.getAge() != entry.matchAge().getAsInt()) continue;
      }
      return Optional.of(entry);
    }
    return Optional.empty();
  }

  public boolean isInternalBreak(Block block) {
    if (block == null) return false;
    return internalBreak.get().contains(BlockKey.of(block.getLocation()));
  }

  public void withInternalBreak(Collection<Block> blocks, Runnable action) {
    Set<BlockKey> set = internalBreak.get();
    if (blocks != null) {
      for (Block block : blocks) {
        if (block == null) continue;
        set.add(BlockKey.of(block.getLocation()));
      }
    }
    try {
      action.run();
    } finally {
      if (blocks != null) {
        for (Block block : blocks) {
          if (block == null) continue;
          set.remove(BlockKey.of(block.getLocation()));
        }
      }
    }
  }

  public boolean isFakeLocked(Player player, Block block) {
    if (player == null || block == null) return false;
    Set<BlockKey> set = fakeLockedByPlayer.get(player.getUniqueId());
    if (set == null) return false;
    return set.contains(BlockKey.of(block.getLocation()));
  }

  public void fakeLock(Player player, Collection<Block> blocks) {
    if (player == null || blocks == null || blocks.isEmpty()) return;
    UUID pid = player.getUniqueId();
    Set<BlockKey> set = fakeLockedByPlayer.computeIfAbsent(pid, k -> ConcurrentHashMap.newKeySet());
    for (Block block : blocks) {
      set.add(BlockKey.of(block.getLocation()));
    }
  }

  public void fakeUnlock(Player player, Collection<Block> blocks) {
    if (player == null || blocks == null || blocks.isEmpty()) return;

    UUID pid = player.getUniqueId();

    Set<BlockKey> lockSet = fakeLockedByPlayer.get(pid);
    if (lockSet != null) {
      for (Block b : blocks) {
        lockSet.remove(BlockKey.of(b.getLocation()));
      }
      if (lockSet.isEmpty()) {
        fakeLockedByPlayer.remove(pid);
      }
    }

    Map<BlockKey, BlockData> view = fakeViewByPlayer.get(pid);
    if (view != null) {
      for (Block b : blocks) {
        view.remove(BlockKey.of(b.getLocation()));
      }
      if (view.isEmpty()) {
        fakeViewByPlayer.remove(pid);
      }
    }

    Map<BlockKey, BlockData> orig = originalViewByPlayer.get(pid);
    if (orig != null) {
      for (Block b : blocks) {
        orig.remove(BlockKey.of(b.getLocation()));
      }
      if (orig.isEmpty()) {
        originalViewByPlayer.remove(pid);
      }
    }
  }

  public void fakeLock(Player player, Block block) {
    if (block == null) return;
    fakeLock(player, List.of(block));
  }

  public void fakeSetView(Player player, Block block, BlockData data) {
    if (player == null || block == null || data == null) return;
    UUID pid = player.getUniqueId();
    Map<BlockKey, BlockData> view = fakeViewByPlayer.computeIfAbsent(pid, k -> new ConcurrentHashMap<>());
    view.put(BlockKey.of(block.getLocation()), data);
  }

  public Optional<BlockData> fakeGetView(Player player, Block block) {
    if (player == null || block == null) return Optional.empty();
    Map<BlockKey, BlockData> view = fakeViewByPlayer.get(player.getUniqueId());
    if (view == null) return Optional.empty();
    return Optional.ofNullable(view.get(BlockKey.of(block.getLocation())));
  }

  public Map<BlockKey, BlockData> fakeViews(Player player) {
    if (player == null) return Map.of();
    Map<BlockKey, BlockData> view = fakeViewByPlayer.get(player.getUniqueId());
    if (view == null || view.isEmpty()) return Map.of();
    return Map.copyOf(view);
  }

  // store and retrieve original blockdata snapshots
  public void storeOriginalIfAbsent(Player player, Block block, BlockData original) {
    if (player == null || block == null || original == null) return;
    UUID pid = player.getUniqueId();
    Map<BlockKey, BlockData> orig = originalViewByPlayer.computeIfAbsent(pid, k -> new ConcurrentHashMap<>());
    BlockKey key = BlockKey.of(block.getLocation());
    orig.putIfAbsent(key, original);
  }

  public Optional<BlockData> getOriginal(Player player, Block block) {
    if (player == null || block == null) return Optional.empty();
    Map<BlockKey, BlockData> orig = originalViewByPlayer.get(player.getUniqueId());
    if (orig == null) return Optional.empty();
    return Optional.ofNullable(orig.get(BlockKey.of(block.getLocation())));
  }

  public boolean isBreakKeyAllowed(RegenEntry entry, BreakKey key) {
    if (entry == null || key == null) return false;
    List<BreakKey> keys = entry.breakKeys();
    if (keys == null || keys.isEmpty()) return false;
    return keys.contains(key);
  }

  public boolean attemptBreak(Player player, Block block, BreakKey key) {
    if (player == null || block == null || key == null) return false;

    if (player.getGameMode().equals(GameMode.CREATIVE)) {
      return false;
    }

    if (isInternalBreak(block)) return false;

    Optional<RegenEntry> match = matchEntry(block);
    if (match.isEmpty()) return false;

    RegenEntry entry = match.get();

    if (!isBreakKeyAllowed(entry, key)) {
      return false;
    }

    if (isFakeLocked(player, block)) {
      return false;
    }

    boolean globalOk = conditions.evaluateAll(entry.globalConditions(), block, player, "nexregen-global");
    if (!globalOk) return false;

    boolean breakOk = conditions.evaluateAll(entry.breakConditions(), block, player, "nexregen-break");
    if (!breakOk) return false;

    fakeLock(player, block);

    AffectedBlockCalculator.Affected affected = AffectedBlockCalculator.computeAffected(block);
    RegenSettings settings = entry.settings();

    List<Block> viewOnlyBlocks = affected.blocksWithoutBreakEvent() != null ? affected.blocksWithoutBreakEvent() : List.of();

    List<Block> allAffectedBlocks;
    if (viewOnlyBlocks.isEmpty()) {
      allAffectedBlocks = affected.blocksWithBreakEvent();
    } else {
      Set<BlockKey> breakSet = new HashSet<>();
      for (Block b : affected.blocksWithBreakEvent()) {
        breakSet.add(BlockKey.of(b.getLocation()));
      }

      ArrayList<Block> tmp = new ArrayList<>(affected.blocksWithBreakEvent());
      for (Block b : viewOnlyBlocks) {
        if (b == null) continue;
        if (!breakSet.contains(BlockKey.of(b.getLocation()))) {
          tmp.add(b);
        }
      }
      allAffectedBlocks = List.copyOf(tmp);
    }

    // Fire synthetic BlockBreakEvents (including the clicked block),
    // but skip the bottom block if replace-only-bottom is enabled.
    Set<Block> syntheticBlocks = new HashSet<>(affected.blocksWithBreakEvent());
    syntheticBlocks.add(block);

    withInternalBreak(syntheticBlocks, () -> {
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
          dropNaturally(b, b.getDrops(tool, player));
        }
      }

      if (settings.dropXp()) {
        // We don't have a real event exp value here; keep consistent and do not grant extra XP by default.
        // If you want XP, you can compute it or mirror vanilla by using b.getExpDrop(...) if available in your API.
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

    fakeLock(player, allAffectedBlocks);

    // Snapshot originals BEFORE faking anything
    for (Block b : allAffectedBlocks) {
      BlockData original = b.getBlockData().clone();
      storeOriginalIfAbsent(player, b, original);
    }

    // Apply fake immediately
    for (Block b : allAffectedBlocks) {
      fakeSetView(player, b, air);
      player.sendBlockChange(b.getLocation(), air);
    }
    for (Block b : replacementTargets) {
      fakeSetView(player, b, replacementData);
      player.sendBlockChange(b.getLocation(), replacementData);
    }

    // Re-assert clicked block next tick
    Bukkit.getScheduler().runTask(plugin, () -> {
      fakeGetView(player, block).ifPresent(view -> player.sendBlockChange(block.getLocation(), view));
    });

    long delayTicks = RegenTimeParser.parseToTicks(entry.regenTimeSpec());
    Bukkit.getScheduler().runTaskLater(plugin, () -> {
      try {
        for (Block b : allAffectedBlocks) {
          BlockData restore = getOriginal(player, b).orElse(finalData);
          player.sendBlockChange(b.getLocation(), restore);
        }
      } finally {
        fakeUnlock(player, allAffectedBlocks);
      }
    }, delayTicks);

    return true;
  }

  private static void dropNaturally(Block at, Collection<ItemStack> drops) {
    if (at == null || drops == null || drops.isEmpty()) return;
    for (ItemStack is : drops) {
      if (is == null || is.getType().isAir() || is.getAmount() <= 0) continue;
      at.getWorld().dropItemNaturally(at.getLocation().add(0.5, 0.5, 0.5), is);
    }
  }

  public void denyInteract(Player player, PlayerInteractEvent event) {
    if (event == null) return;
    event.setCancelled(true);
    event.setUseInteractedBlock(Event.Result.DENY);
    event.setUseItemInHand(Event.Result.DENY);

    if (player == null) return;
    Block clicked = event.getClickedBlock();
    if (clicked == null) return;

    fakeGetView(player, clicked).ifPresent(view ->
        Bukkit.getScheduler().runTask(plugin, () -> {
          if (!player.isOnline()) return;
          player.sendBlockChange(clicked.getLocation(), view);
        })
    );
  }

  public void cleanupPlayer(Player player) {
    if (player == null) return;
    UUID pid = player.getUniqueId();
    fakeLockedByPlayer.remove(pid);
    fakeViewByPlayer.remove(pid);
    originalViewByPlayer.remove(pid);
  }

  public void clearThreadLocals() {
    internalBreak.remove();
  }
}