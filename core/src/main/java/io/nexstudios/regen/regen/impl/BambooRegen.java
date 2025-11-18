package io.nexstudios.regen.regen.impl;

import io.nexstudios.nexus.bukkit.NexusPlugin;
import io.nexstudios.regen.NexRegen;
import io.nexstudios.regen.regen.NexusRegen;
import io.nexstudios.regen.regen.RegenReader;
import io.nexstudios.regen.regen.model.Regenerator;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Spaltenbasierter Regen für BAMBOO, analog zu CactusRegen / SugarCaneRegen.
 * Leaves (none / small / large) werden über originalBlockData korrekt wiederhergestellt.
 * Drops werden wie in DefaultRegen gesteuert (nur wenn drop-items: true).
 * Für jeden Bamboo-Block der Säule gibt es genau einen BlockBreakEvent -> XP für jeden Block.
 */
public class BambooRegen implements NexusRegen {

    private static final Set<String> IGNORE_LOCATIONS = ConcurrentHashMap.newKeySet();
    private static final ConcurrentHashMap<String, Long> SCHEDULED = new ConcurrentHashMap<>();

    private String key(Block block) {
        Location loc = block.getLocation();
        return loc.getWorld().getName() + ":" + loc.getBlockX() + ":" + loc.getBlockY() + ":" + loc.getBlockZ();
    }

    @Override
    public void handle(BlockBreakEvent event) {

        Block block = event.getBlock();

        // Nur Bamboo behandeln
        if (block.getType() != Material.BAMBOO) {
            return;
        }

        String locKey = key(block);

        // Ist das ein synthetisch abgebauter Block (von player.breakBlock)?
        boolean syntheticBreak = IGNORE_LOCATIONS.remove(locKey);

        long now = System.currentTimeMillis();
        Long scheduledAt = SCHEDULED.get(locKey);
        if (scheduledAt != null && scheduledAt > now) {
            // Regen noch pending -> keine neue Planung,
            // aber wir wollen dennoch Drops gemäß Config steuern.
            handleRegen(event, syntheticBreak, /*skipColumnLogic=*/true);
            return;
        } else if (scheduledAt != null) {
            // alter Eintrag, aufräumen
            SCHEDULED.remove(locKey);
        }

        handleRegen(event, syntheticBreak, /*skipColumnLogic=*/false);
    }

    private void handleRegen(BlockBreakEvent event,
                             boolean syntheticBreak,
                             boolean skipColumnLogic) {

        Player player = event.getPlayer();
        Block block = event.getBlock();

        if (player.getGameMode() == GameMode.CREATIVE) {
            return;
        }

        Material brokenType = block.getType();
        RegenReader regenReader = NexRegen.getInstance().getRegenReader();
        var index = regenReader.getBlocksByMaterial();

        var indexedBlocks = index.get(brokenType);
        if (indexedBlocks == null || indexedBlocks.isEmpty()) {
            return;
        }

        for (RegenReader.IndexedRegenBlock indexed : indexedBlocks) {

            String regenID = indexed.getRegeneratorId();
            Regenerator regenerator = indexed.getRegenerator();

            if (regenerator == null) {
                NexRegen.nexusLogger.error(List.of(
                        "Could not handle bamboo block break event for block " + block.getType() + " at " + block.getLocation(),
                        "Regenerator with ID " + regenID + " is null"
                ));
                continue;
            }

            if(regenerator.getConditions() != null) {
                if (!NexusPlugin.getInstance().getConditionFactory().checkConditions(
                        player,
                        block.getLocation(),
                        regenerator.getConditions().getConditions()
                )) {
                    //event.setCancelled(true);
                    continue;
                }
            }

            if(indexed.getRegenBlock().getBreakConditions() != null) {
                if (!NexusPlugin.getInstance().getConditionFactory().checkConditions(
                        player,
                        block.getLocation(),
                        indexed.getRegenBlock().getBreakConditions()
                )) {
                    //event.setCancelled(true);
                    continue;
                }
            }

            Regenerator.RegenBlock regenBlock = indexed.getRegenBlock();
            if (regenBlock == null) {
                NexRegen.nexusLogger.error(List.of(
                        "Could not handle bamboo block break event for block " + block.getType() + " at " + block.getLocation(),
                        "Regen Block in Regenerator file " + regenID + ".yml is null!"
                ));
                continue;
            }

            if (regenBlock.getBlockMaterial() == null ||
                    regenBlock.getBlockMaterial() != brokenType) {
                continue;
            }

            if (regenBlock.getReplacementMaterial() == null ||
                    !regenBlock.getReplacementMaterial().isBlock()) {
                NexRegen.nexusLogger.error(List.of(
                        "Could not handle bamboo block break event for block " + block.getType() + " at " + block.getLocation(),
                        "Replacement block in Regenerator file " + regenID + ".yml is not a valid block!"
                ));
                continue;
            }

            // =======================
            // Drops exakt wie DefaultRegen
            // =======================
            event.setDropItems(false); // Vanilla-Drops immer aus

            if (regenBlock.getSettings().isDropItems()) {
                // leichter Offset, damit Items nicht im Block stecken bleiben
                Location dropLocation = block.getLocation().clone().add(0, 0.5, 0);

                block.getDrops().forEach(singleDrop -> {

                    if (regenBlock.getSettings().isAutoPickup()) {
                        Map<Integer, ItemStack> leftover = player.getInventory().addItem(singleDrop);
                        leftover.values().forEach(rest ->
                                player.getWorld().dropItemNaturally(player.getLocation(), rest)
                        );
                    } else {
                        dropLocation.getWorld().dropItemNaturally(dropLocation, singleDrop);
                    }
                });
            }

            if (!regenBlock.getSettings().isDropXp()) {
                event.setExpToDrop(0);
            }

            // Für synthetische Breaks (player.breakBlock für andere Segmente)
            // nur Drops/XP steuern, KEINE Säulen-Logik / Regenplanung
            if (syntheticBreak || skipColumnLogic) {
                return;
            }

            // =======================
            // Säulen-Regen nur für den "ersten" echten Break
            // =======================

            // ganze Bamboo-Säule ermitteln
            List<Block> column = collectColumn(block, Material.BAMBOO);

            // Regen für die gesamte Säule planen
            scheduleColumnRegen(column, regenBlock, regenID, Material.BAMBOO);

            // alle anderen Blöcke der Säule mit player.breakBlock abbauen
            breakWholeColumnWithPlayer(column, block, player, regenBlock);

            // Event NICHT canceln – Skill-Plugins sehen alle BlockBreakEvents
            return;
        }
    }

    private List<Block> collectColumn(Block origin, Material material) {
        List<Block> column = new ArrayList<>();
        World world = origin.getWorld();
        int minY = world.getMinHeight();
        int maxY = world.getMaxHeight() - 1;

        // nach unten bis zum ersten Nicht-Material-Block
        Block base = origin;
        while (base.getY() > minY) {
            Block below = base.getRelative(0, -1, 0);
            if (below.getType() != material) {
                break;
            }
            base = below;
        }

        // von base nach oben bis zum letzten Material-Block
        Block current = base;
        while (current.getY() <= maxY && current.getType() == material) {
            column.add(current);
            current = current.getRelative(0, 1, 0);
        }

        return column;
    }

    private void scheduleColumnRegen(List<Block> column,
                                     Regenerator.RegenBlock regenBlock,
                                     String regeneratorID,
                                     Material material) {

        long delaySeconds = NexRegen.getInstance()
                .getRegenFactory()
                .parseRegenTimeToSeconds(regenBlock.getRegenTime());

        long regenAt = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(delaySeconds);

        String originalMaterial = material.name();

        for (Block bambooBlock : column) {
            String locKey = key(bambooBlock);

            // aktuelles BlockData inkl. leaves, age, etc. speichern
            String originalBlockData = bambooBlock.getBlockData().getAsString();

            SCHEDULED.put(locKey, regenAt);

            NexRegen.getInstance()
                    .getRegenFactory()
                    .scheduleRegen(
                            bambooBlock,
                            delaySeconds,
                            TimeUnit.SECONDS,
                            regeneratorID,
                            originalMaterial,
                            originalBlockData,
                            originalMaterial,
                            originalBlockData
                    );
        }
    }

    /**
     * Bricht die gesamte Säule ab, außer dem Block, der dieses Event ausgelöst hat.
     * Dadurch gibt es genau so viele BlockBreakEvents (und XP) wie Bamboo-Blöcke.
     */
    private void breakWholeColumnWithPlayer(List<Block> column,
                                            Block originalBlock,
                                            Player player,
                                            Regenerator.RegenBlock regenBlock) {

        if (column.isEmpty()) {
            return;
        }

        int originalIndex = column.indexOf(originalBlock);
        if (originalIndex < 0) {
            // Fallback: original nicht gefunden, dann nehmen wir den Basiseintrag
            originalIndex = 0;
        }

        for (int i = 0; i < column.size(); i++) {
            if (i == originalIndex) {
                // diesen Block hat der Spieler schon abgebaut (dieses Event)
                continue;
            }

            Block bambooBlock = column.get(i);

            IGNORE_LOCATIONS.add(key(bambooBlock));

            boolean success = player.breakBlock(bambooBlock);
            if (!success) {
                IGNORE_LOCATIONS.remove(key(bambooBlock));
                bambooBlock.setType(Material.AIR, regenBlock.getSettings().isApplyPhysics());
            }
        }
    }
}