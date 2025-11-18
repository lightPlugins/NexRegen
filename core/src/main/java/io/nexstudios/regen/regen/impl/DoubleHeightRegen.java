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
import org.bukkit.block.Block;
import org.bukkit.block.data.Bisected;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Player;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.inventory.ItemStack;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Regen für zweiblöckige Pflanzen (z.B. TALL_GRASS, LARGE_FERN, SUNFLOWER, etc.).
 * Egal ob oben oder unten abgebaut wird, beide Hälften werden entfernt und später
 * wiederhergestellt. Drops werden wie in DefaultRegen gesteuert.
 */
public class DoubleHeightRegen implements NexusRegen {

    private static final Set<String> IGNORE_LOCATIONS = ConcurrentHashMap.newKeySet();
    private static final Map<String, Long> SCHEDULED = new ConcurrentHashMap<>();

    private String key(Block block) {
        Location loc = block.getLocation();
        return loc.getWorld().getName() + ":" + loc.getBlockX() + ":" + loc.getBlockY() + ":" + loc.getBlockZ();
    }

    @Override
    public void handle(BlockBreakEvent event) {

        Block block = event.getBlock();
        Material type = block.getType();

        switch (type) {
            case TALL_GRASS,
                 LARGE_FERN,
                 SUNFLOWER,
                 TALL_SEAGRASS,
                 ROSE_BUSH,
                 LILAC,
                 PITCHER_PLANT,
                 PEONY:
                break;
            default:
                return;
        }

        String locKey = key(block);

        // synthetischer Break (zweite Hälfte via player.breakBlock)
        boolean syntheticBreak = IGNORE_LOCATIONS.remove(locKey);

        long now = System.currentTimeMillis();
        Long scheduledAt = SCHEDULED.get(locKey);
        if (scheduledAt != null && scheduledAt > now) {
            // Regen noch pending -> keine neue Planung, aber Drops/XP steuern
            handleRegen(event, syntheticBreak, true);
            return;
        } else if (scheduledAt != null) {
            SCHEDULED.remove(locKey);
        }

        handleRegen(event, syntheticBreak, false);
    }

    private void handleRegen(BlockBreakEvent event,
                             boolean syntheticBreak,
                             boolean skipPairLogic) {

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
                        "Could not handle double height block break event for block " + block.getType() + " at " + block.getLocation(),
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
                        "Could not handle double height block break event for block " + block.getType() + " at " + block.getLocation(),
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
                        "Could not handle double height block break event for block " + block.getType() + " at " + block.getLocation(),
                        "Replacement block in Regenerator file " + regenID + ".yml is not a valid block!"
                ));
                continue;
            }

            // =======================
            // Drops wie DefaultRegen
            // =======================
            event.setDropItems(false); // Vanilla-Drops aus

            if (regenBlock.getSettings().isDropItems()) {
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

            // bei synthetischen Breaks (zweite Hälfte) nur Drops/XP steuern
            if (syntheticBreak || skipPairLogic) {
                return;
            }

            // =======================
            // Zweiblöckige Logik
            // =======================

            List<Block> pair = collectPair(block);
            if (pair.isEmpty()) {
                return;
            }

            // Regen für beide Hälften planen
            schedulePairRegen(pair, regenBlock, regenID);

            // Andere Hälfte mit player.breakBlock abbauen
            breakOtherHalfWithPlayer(pair, block, player, regenBlock);

            // Event NICHT canceln – Skills sehen beide BlockBreakEvents
            return;
        }
    }

    /**
     * Ermittelt beide Hälften des zweiblöckigen Blocks (oben/unten).
     */
    private List<Block> collectPair(Block origin) {
        BlockData data = origin.getBlockData();
        if (!(data instanceof Bisected bisected)) {
            return List.of(); // kein zweiblöckiger Block
        }

        List<Block> pair = new ArrayList<>(2);
        Block other;

        if (bisected.getHalf() == Bisected.Half.TOP) {
            pair.add(origin);
            other = origin.getRelative(0, -1, 0);
        } else {
            // BOTTOM
            pair.add(origin);
            other = origin.getRelative(0, 1, 0);
        }

        if (other.getType() == origin.getType() && other.getBlockData() instanceof Bisected) {
            pair.add(other);
        }

        return pair;
    }

    /**
     * Regen für beide Hälften einplanen. Beide werden später separat regeneriert.
     */
    private void schedulePairRegen(List<Block> pair,
                                   Regenerator.RegenBlock regenBlock,
                                   String regeneratorID) {

        long delaySeconds = NexRegen.getInstance()
                .getRegenFactory()
                .parseRegenTimeToSeconds(regenBlock.getRegenTime());

        long regenAt = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(delaySeconds);

        for (Block b : pair) {
            String locKey = key(b);

            String originalMaterial = b.getType().name();
            String originalBlockData = b.getBlockData().getAsString();

            SCHEDULED.put(locKey, regenAt);

            NexRegen.getInstance()
                    .getRegenFactory()
                    .scheduleRegen(
                            b,
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
     * Bricht die jeweils andere Hälfte mit player.breakBlock ab, damit ein eigener
     * BlockBreakEvent entsteht (für XP & Events). Die Drops werden in handleRegen
     * wieder über die Config gesteuert.
     */
    private void breakOtherHalfWithPlayer(List<Block> pair,
                                          Block originalBlock,
                                          Player player,
                                          Regenerator.RegenBlock regenBlock) {

        if (pair.size() < 2) {
            return;
        }

        Block other = pair.get(0).equals(originalBlock) ? pair.get(1) : pair.get(0);

        IGNORE_LOCATIONS.add(key(other));

        boolean success = player.breakBlock(other);
        if (!success) {
            IGNORE_LOCATIONS.remove(key(other));
            other.setType(regenBlock.getReplacementMaterial(), regenBlock.getSettings().isApplyPhysics());
        }
    }
}