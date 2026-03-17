package io.nexstudios.nexregen.service;

import io.nexstudios.nexregen.service.model.RegenEntry;
import io.nexstudios.nexregen.service.util.BlockKey;
import io.nexstudios.serviceregistry.di.Service;
import org.bukkit.block.Block;
import org.bukkit.block.data.Ageable;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public final class RegenManager implements Service {

  private final JavaPlugin plugin;
  private volatile List<RegenEntry> entries;
  private final NexLogicConditionFacade conditions;

  private final Map<UUID, Set<BlockKey>> fakeLockedByPlayer = new ConcurrentHashMap<>();
  private final Map<UUID, Map<BlockKey, BlockData>> fakeViewByPlayer = new ConcurrentHashMap<>();

  // NEW: original block state snapshot (per player) to restore after regen-time
  private final Map<UUID, Map<BlockKey, BlockData>> originalViewByPlayer = new ConcurrentHashMap<>();

  private final ThreadLocal<Set<BlockKey>> internalBreak = ThreadLocal.withInitial(HashSet::new);

  public RegenManager(JavaPlugin plugin, List<RegenEntry> entries, NexLogicConditionFacade conditions) {
    this.plugin = plugin;
    this.entries = List.copyOf(entries);
    this.conditions = conditions;
  }

  public JavaPlugin plugin() {
    return plugin;
  }

  public NexLogicConditionFacade conditions() {
    return conditions;
  }

  public void reloadEntries(List<RegenEntry> newEntries) {
    this.entries = List.copyOf(newEntries);
  }

  public Optional<RegenEntry> matchEntry(Block block) {
    for (RegenEntry e : entries) {
      if (block.getType() != e.matchMaterial()) continue;
      if (e.matchAge().isPresent()) {
        if (!(block.getBlockData() instanceof Ageable ageable)) continue;
        if (ageable.getAge() != e.matchAge().getAsInt()) continue;
      }
      return Optional.of(e);
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
      for (Block b : blocks) {
        if (b == null) continue;
        set.add(BlockKey.of(b.getLocation()));
      }
    }
    try {
      action.run();
    } finally {
      if (blocks != null) {
        for (Block b : blocks) {
          if (b == null) continue;
          set.remove(BlockKey.of(b.getLocation()));
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
    for (Block b : blocks) {
      set.add(BlockKey.of(b.getLocation()));
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

  // NEW: store and retrieve original blockdata snapshots
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
}