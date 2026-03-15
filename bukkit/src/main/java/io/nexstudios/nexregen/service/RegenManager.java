package io.nexstudios.nexregen.service;

import io.nexstudios.nexregen.service.model.RegenEntry;
import io.nexstudios.nexregen.service.model.RegenSettings;
import io.nexstudios.nexregen.service.util.BlockDataSpec;
import io.nexstudios.nexregen.service.util.BlockKey;
import io.nexstudios.nexregen.service.util.RegenTimeParser;
import io.nexstudios.serviceregistry.di.Service;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.Ageable;
import org.bukkit.block.data.BlockData;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public final class RegenManager implements Service {

  private final JavaPlugin plugin;
  private final List<RegenEntry> entries;
  private final NexLogicConditionFacade conditions;

  private final Map<BlockKey, Pending> pending = new ConcurrentHashMap<>();
  private final ThreadLocal<Set<BlockKey>> internalBreak = ThreadLocal.withInitial(HashSet::new);

  public RegenManager(JavaPlugin plugin, List<RegenEntry> entries, NexLogicConditionFacade conditions) {
    this.plugin = plugin;
    this.entries = List.copyOf(entries);
    this.conditions = conditions;
  }

  public NexLogicConditionFacade conditions() {
    return conditions;
  }

  public boolean isInternalBreak(Block block) {
    return internalBreak.get().contains(BlockKey.of(block.getLocation()));
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

  public void applyReplacementAndScheduleRegen(Block baseBlock, RegenEntry entry, List<RestoreAction> extraRestoresOnRegen) {
    BlockKey key = BlockKey.of(baseBlock.getLocation());
    if (pending.containsKey(key)) return;

    BlockData replacement = BlockDataSpec.toBlockDataWithoutAge(entry.replacementBlockDataSpec());
    replacement = BlockDataSpec.applyAgeIfPossible(replacement, entry.replacementAge().isPresent() ? Optional.of(entry.replacementAge().getAsInt()) : Optional.empty());

    BlockData fin = BlockDataSpec.toBlockDataWithoutAge(entry.finalBlockDataSpec());
    fin = BlockDataSpec.applyAgeIfPossible(fin, entry.finalAge().isPresent() ? Optional.of(entry.finalAge().getAsInt()) : Optional.empty());

    placeBlockData(baseBlock, replacement, entry.settings());

    long delayTicks = RegenTimeParser.parseToTicks(entry.regenTimeSpec());
    BlockData finalFin = fin;
    BukkitTask task = Bukkit.getScheduler().runTaskLater(plugin, () -> {
      try {
        Block current = baseBlock.getWorld().getBlockAt(baseBlock.getX(), baseBlock.getY(), baseBlock.getZ());
        placeBlockData(current, finalFin, entry.settings());

        for (RestoreAction a : extraRestoresOnRegen) {
          a.apply(entry.settings());
        }
      } finally {
        pending.remove(key);
      }
    }, delayTicks);

    pending.put(
        key,
        new Pending(
            baseBlock.getWorld().getUID(),
            baseBlock.getX(),
            baseBlock.getY(),
            baseBlock.getZ(),
            fin,
            entry.settings(),
            task,
            List.copyOf(extraRestoresOnRegen)
        )
    );
  }

  public void setToAir(Block block, RegenSettings settings) {
    placeBlockData(block, Bukkit.createBlockData("minecraft:air"), settings);
  }

  public void forceCompleteAllPendingOnShutdown() {
    for (Pending p : pending.values()) {
      if (p.task != null) {
        p.task.cancel();
      }
      World w = Bukkit.getWorld(p.worldId);
      if (w == null) continue;

      Block b = w.getBlockAt(p.x, p.y, p.z);
      placeBlockData(b, p.finalState, p.settings);

      for (RestoreAction a : p.extraRestoresOnRegen) {
        a.apply(p.settings);
      }
    }
    pending.clear();
  }

  public <T> T withInternalBreak(Collection<Block> blocks, java.util.concurrent.Callable<T> action) {
    Set<BlockKey> set = internalBreak.get();
    for (Block b : blocks) {
      set.add(BlockKey.of(b.getLocation()));
    }
    try {
      return action.call();
    } catch (Exception e) {
      throw new RuntimeException(e);
    } finally {
      for (Block b : blocks) {
        set.remove(BlockKey.of(b.getLocation()));
      }
    }
  }

  private static void placeBlockData(Block block, BlockData data, RegenSettings settings) {
    boolean applyPhysics = settings.applyPhysics();
    block.setBlockData(data, applyPhysics);
  }

  public record RestoreAction(UUID worldId, int x, int y, int z, BlockData data) {
    public void apply(RegenSettings settings) {
      World w = Bukkit.getWorld(worldId);
      if (w == null) return;
      Block b = w.getBlockAt(x, y, z);
      b.setBlockData(data, settings.applyPhysics());
    }
  }

  private static final class Pending {
    private final UUID worldId;
    private final int x;
    private final int y;
    private final int z;
    private final BlockData finalState;
    private final RegenSettings settings;
    private final BukkitTask task;
    private final List<RestoreAction> extraRestoresOnRegen;

    private Pending(
        UUID worldId,
        int x,
        int y,
        int z,
        BlockData finalState,
        RegenSettings settings,
        BukkitTask task,
        List<RestoreAction> extraRestoresOnRegen
    ) {
      this.worldId = worldId;
      this.x = x;
      this.y = y;
      this.z = z;
      this.finalState = finalState;
      this.settings = settings;
      this.task = task;
      this.extraRestoresOnRegen = extraRestoresOnRegen;
    }
  }
}