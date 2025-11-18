package io.nexstudios.regen.regen;

import io.nexstudios.nexus.bukkit.NexusPlugin;
import io.nexstudios.nexus.bukkit.database.api.DbAsyncHelper;
import io.nexstudios.regen.NexRegen;
import io.nexstudios.regen.regen.model.ActiveRegen;
import io.nexstudios.regen.regen.model.Regenerator;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.plugin.PluginManager;
import org.bukkit.scheduler.BukkitRunnable;

import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

public class RegenFactory implements Listener {

    // NICHT static -> pro Plugin-Instanz eigene Handlerliste
    private final List<NexusRegen> vanillaBlocksRegen = new ArrayList<>();

    // Aktive Regens im Speicher (key = world:x:y:z)
    private final ConcurrentHashMap<String, ActiveRegen> activeRegens = new ConcurrentHashMap<>();

    private final DbAsyncHelper dbHelper;

    public RegenFactory(PluginManager pluginManager, DbAsyncHelper dbHelper) {
        this.dbHelper = dbHelper;
        pluginManager.registerEvents(this, NexRegen.getInstance());

        loadActiveRegensFromDatabase();
        startRegenScheduler();
    }

    private String key(String world, int x, int y, int z) {
        return world + ":" + x + ":" + y + ":" + z;
    }

    public void registerVanillaRegenHandler(NexusRegen regen) {
        vanillaBlocksRegen.add(regen);
    }

    private void startRegenScheduler() {
        final long intervalTicks = 5L;

        new BukkitRunnable() {
            @Override
            public void run() {
                long now = System.currentTimeMillis();

                List<ActiveRegen> due = new ArrayList<>();

                for (ActiveRegen active : activeRegens.values()) {
                    if (active != null && active.getRegenAt() <= now) {
                        due.add(active);
                    }
                }

                if (due.isEmpty()) {
                    return;
                }

                for (ActiveRegen active : due) {
                    regenerateBlock(active);
                }
            }
        }.runTaskTimer(NexRegen.getInstance(), intervalTicks, intervalTicks);
    }

    private void loadActiveRegensFromDatabase() {
        String sql = "SELECT id, world, x, y, z, original_material, original_block_data, " +
                "replacement_material, replacement_block_data, regen_at, regen_id " +
                "FROM nexregen_active_regen";

        dbHelper.queryAsync(sql, this::mapActiveRegen).whenComplete((list, throwable) -> {
            if (throwable != null) {
                NexRegen.nexusLogger.error(List.of(
                        "Failed to load active regens from database.",
                        throwable.getMessage()
                ));
                return;
            }

            long now = System.currentTimeMillis();

            for (ActiveRegen active : list) {
                if (active == null) continue;

                if (active.getRegenAt() <= now) {
                    regenerateBlock(active);
                } else {
                    activeRegens.put(
                            key(active.getWorldName(), active.getX(), active.getY(), active.getZ()),
                            active
                    );
                }
            }

            NexRegen.nexusLogger.info("Loaded " + list.size() + " active regens from database.");
        });
    }

    private ActiveRegen mapActiveRegen(ResultSet rs) {
        try {
            ActiveRegen active = new ActiveRegen();
            active.setId(rs.getLong("id"));
            active.setWorldName(rs.getString("world"));
            active.setX(rs.getInt("x"));
            active.setY(rs.getInt("y"));
            active.setZ(rs.getInt("z"));
            active.setOriginalMaterial(rs.getString("original_material"));
            active.setOriginalBlockData(rs.getString("original_block_data"));
            try {
                active.setReplacementMaterial(rs.getString("replacement_material"));
                active.setReplacementBlockData(rs.getString("replacement_block_data"));
            } catch (Exception ignored) {
            }
            active.setRegenAt(rs.getLong("regen_at"));
            active.setRegenId(rs.getString("regen_id"));
            return active;
        } catch (Exception e) {
            NexRegen.nexusLogger.error(List.of(
                    "Failed to map ActiveRegen from ResultSet.",
                    e.getMessage()
            ));
            return null;
        }
    }

    public void addActiveRegenAsync(ActiveRegen active) {
        String sql = "INSERT INTO nexregen_active_regen " +
                "(world, x, y, z, original_material, original_block_data, " +
                "replacement_material, replacement_block_data, regen_at, regen_id) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";

        dbHelper.updateAsync(
                sql,
                active.getWorldName(),
                active.getX(),
                active.getY(),
                active.getZ(),
                active.getOriginalMaterial(),
                active.getOriginalBlockData(),
                active.getReplacementMaterial(),
                active.getReplacementBlockData(),
                active.getRegenAt(),
                active.getRegenId()
        ).whenComplete((rows, throwable) -> {
            if (throwable != null) {
                NexRegen.nexusLogger.error(List.of(
                        "Failed to insert ActiveRegen into database.",
                        throwable.getMessage()
                ));
            }
        });

        activeRegens.put(key(active.getWorldName(), active.getX(), active.getY(), active.getZ()), active);
    }

    public void removeActiveRegenAsync(ActiveRegen active) {
        if (active.getId() != null) {
            String sql = "DELETE FROM nexregen_active_regen WHERE id = ?";
            dbHelper.updateAsync(sql, active.getId()).whenComplete((rows, throwable) -> {
                if (throwable != null) {
                    NexRegen.nexusLogger.error(List.of(
                            "Failed to delete ActiveRegen from database by id.",
                            throwable.getMessage()
                    ));
                }
            });
        } else {
            String sql = "DELETE FROM nexregen_active_regen WHERE world = ? AND x = ? AND y = ? AND z = ?";
            dbHelper.updateAsync(
                    sql,
                    active.getWorldName(),
                    active.getX(),
                    active.getY(),
                    active.getZ()
            ).whenComplete((rows, throwable) -> {
                if (throwable != null) {
                    NexRegen.nexusLogger.error(List.of(
                            "Failed to delete ActiveRegen from database by location.",
                            throwable.getMessage()
                    ));
                }
            });
        }

        activeRegens.remove(key(active.getWorldName(), active.getX(), active.getY(), active.getZ()));
    }

    public void scheduleRegen(Block block,
                              long delay,
                              TimeUnit unit,
                              String regenId,
                              String originalMaterial,
                              String originalBlockData) {

        scheduleRegen(
                block,
                delay,
                unit,
                regenId,
                originalMaterial,
                originalBlockData,
                null,
                null
        );
    }

    public void scheduleRegen(Block block,
                              long delay,
                              TimeUnit unit,
                              String regenId,
                              String originalMaterial,
                              String originalBlockData,
                              String targetMaterial,
                              String targetBlockData) {

        long delayMs = unit.toMillis(delay);
        long regenAt = System.currentTimeMillis() + delayMs;

        ActiveRegen active = new ActiveRegen();
        active.setWorldName(block.getWorld().getName());
        active.setX(block.getX());
        active.setY(block.getY());
        active.setZ(block.getZ());

        active.setOriginalMaterial(originalMaterial);
        active.setOriginalBlockData(originalBlockData);

        active.setReplacementMaterial(targetMaterial);
        active.setReplacementBlockData(targetBlockData);

        active.setRegenAt(regenAt);
        active.setRegenId(regenId);

        addActiveRegenAsync(active);
    }

    private void regenerateBlock(ActiveRegen active) {
        Location loc = active.toLocation();
        if (loc == null) {
            NexRegen.nexusLogger.error(List.of(
                    "Could not regenerate block: world is null.",
                    "World: " + active.getWorldName()
            ));
            removeActiveRegenAsync(active);
            return;
        }

        Block block = loc.getBlock();

        try {
            String materialName =
                    active.getReplacementMaterial() != null && !active.getReplacementMaterial().isEmpty()
                            ? active.getReplacementMaterial()
                            : active.getOriginalMaterial();

            String blockDataString =
                    active.getReplacementBlockData() != null && !active.getReplacementBlockData().isEmpty()
                            ? active.getReplacementBlockData()
                            : active.getOriginalBlockData();

            Material material = Material.valueOf(materialName);
            block.setType(material, false);

            if (blockDataString != null && !blockDataString.isEmpty()) {
                try {
                    block.setBlockData(Bukkit.createBlockData(blockDataString), false);
                } catch (IllegalArgumentException ex) {
                    NexRegen.nexusLogger.error(List.of(
                            "Failed to apply block data during regeneration.",
                            "Location: " + loc,
                            "Data: " + blockDataString,
                            ex.getMessage()
                    ));
                }
            }
        } catch (IllegalArgumentException e) {
            NexRegen.nexusLogger.error(List.of(
                    "Failed to regenerate block: invalid material.",
                    "Material: " + active.getOriginalMaterial(),
                    e.getMessage()
            ));
        }

        removeActiveRegenAsync(active);
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        for (NexusRegen handler : vanillaBlocksRegen) {
            if (event.isCancelled()) {
                return;
            }
            handler.handle(event);
        }
    }


    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = false)
    public void onBlockBreakGuard(BlockBreakEvent event) {

        var player = event.getPlayer();
        if (player.getGameMode() == GameMode.CREATIVE) {
            return;
        }

        Block block = event.getBlock();
        Material type = block.getType();

        RegenReader regenReader = NexRegen.getInstance().getRegenReader();
        if (regenReader == null) {
            return;
        }

        var index = regenReader.getBlocksByMaterial();
        var indexedBlocks = index.get(type);

        // Wenn es für dieses Material KEINEN Regenerator gibt: Abbau verbieten
        if (indexedBlocks == null || indexedBlocks.isEmpty()) {
            event.setCancelled(true);
            return;
        }

        // Prüfen, ob mindestens EIN Regenerator an dieser Location „aktiv“ ist
        boolean allowedHere = false;

        for (RegenReader.IndexedRegenBlock indexed : indexedBlocks) {
            Regenerator regenerator = indexed.getRegenerator();
            Regenerator.RegenBlock regenBlock = indexed.getRegenBlock();

            if (regenerator == null || regenBlock == null) {
                continue;
            }

            // enable-conditions des Regenerators (z.B. in-region: barn/palegarden)
            if (regenerator.getConditions() != null &&
                    regenerator.getConditions().getConditions() != null &&
                    !regenerator.getConditions().getConditions().isEmpty()) {

                if (!NexusPlugin.getInstance().getConditionFactory().checkConditions(
                        player,
                        block.getLocation(),
                        regenerator.getConditions().getConditions()
                )) {
                    // diese Datei passt hier nicht (falsche Region etc.)
                    continue;
                }
            }

            // break-conditions dieses Blocks
            if (regenBlock.getBreakConditions() != null &&
                    !regenBlock.getBreakConditions().isEmpty()) {

                if (!NexusPlugin.getInstance().getConditionFactory().checkConditions(
                        player,
                        block.getLocation(),
                        regenBlock.getBreakConditions()
                )) {
                    continue;
                }
            }

            // Wenn wir hier ankommen, gibt es mindestens einen passenden Regenerator
            allowedHere = true;
            break;
        }

        // Kein passender Regenerator an dieser Location -> Abbau verbieten
        if (!allowedHere) {
            event.setCancelled(true);
        }
    }

    public long parseRegenTimeToSeconds(String regenTime) {
        if (regenTime == null || regenTime.isEmpty()) {
            return 5L;
        }

        if (regenTime.contains("-")) {
            String[] parts = regenTime.split("-");
            try {
                long min = Long.parseLong(parts[0].trim());
                long max = Long.parseLong(parts[1].trim());
                if (max < min) {
                    long tmp = min;
                    min = max;
                    max = tmp;
                }
                long diff = max - min;
                return min + (long) (Math.random() * (diff + 1));
            } catch (NumberFormatException e) {
                NexRegen.nexusLogger.error(List.of(
                        "Invalid regen-time format. Expected 'min-max' in seconds.",
                        "Value: " + regenTime,
                        "Fallback to 5 seconds."
                ));
                return 5L;
            }
        }

        try {
            return Long.parseLong(regenTime.trim());
        } catch (NumberFormatException e) {
            NexRegen.nexusLogger.error(List.of(
                    "Invalid regen-time value. Expected integer seconds.",
                    "Value: " + regenTime
            ));
            return 5L;
        }
    }
}