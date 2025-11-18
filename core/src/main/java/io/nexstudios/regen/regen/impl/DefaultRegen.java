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
import org.bukkit.entity.Player;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.inventory.ItemStack;

import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

public class DefaultRegen implements NexusRegen {

    @Override
    public void handle(BlockBreakEvent event) {

        switch (event.getBlock().getType()) {
            case SUGAR_CANE, COCOA, CACTUS, BAMBOO, CARROTS, POTATOES, WHEAT, BEETROOTS, TALL_GRASS,
                 LARGE_FERN, ROSE_BUSH, LILAC, PEONY, PITCHER_PLANT, TALL_SEAGRASS, SWEET_BERRY_BUSH, KELP_PLANT, SUNFLOWER,
                 POINTED_DRIPSTONE, CAVE_VINES, CAVE_VINES_PLANT:
                return;
            default:
                handleRegen(event);
        }
    }

    private void handleRegen(BlockBreakEvent event) {

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
                        "Could not handle block break event for block " + block.getType() + " at " + block.getLocation(),
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
                        "Could not handle block break event for block " + block.getType() + " at " + block.getLocation(),
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
                        "Could not handle block break event for block " + block.getType() + " at " + block.getLocation(),
                        "Replacement block in Regenerator file " + regenID + ".yml is not a valid block!"
                ));
                continue;
            }

            event.setDropItems(false);

            if (regenBlock.getSettings().isDropItems()) {
                // offset because a drop can stick in blocks
                Location dropLocation = block.getLocation().clone().add(0, 0.5, 0);

                event.getBlock().getDrops().forEach(singleDrop -> {

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

            handleSingleRegen(event, regenBlock, regenID);
            return;
        }
    }

    private void handleSingleRegen(BlockBreakEvent event,
                                   Regenerator.RegenBlock regenBlock,
                                   String regeneratorID) {

        Block block = event.getBlock();

        String originalMaterial = block.getType().name();
        String originalBlockData = block.getBlockData().getAsString();

        long delaySeconds = NexRegen.getInstance()
                .getRegenFactory()
                .parseRegenTimeToSeconds(regenBlock.getRegenTime());

        // Event NICHT canceln, damit Skill-Plugins XP vergeben können.
        // event.setCancelled(true); // entfernt

        Material replaceMaterial = regenBlock.getReplacementMaterial();
        boolean applyPhysics = regenBlock.getSettings().isApplyPhysics();

        final Material finalReplaceMaterial = replaceMaterial;
        final boolean finalApplyPhysics = applyPhysics;

        // Replacement im nächsten Tick setzen, nachdem Bukkit den Block wirklich entfernt hat
        Bukkit.getScheduler().runTask(NexRegen.getInstance(), () -> {
            Block b = event.getBlock();
            if (b.getType() == Material.AIR) {
                b.setType(finalReplaceMaterial, finalApplyPhysics);
            }
        });

        NexRegen.getInstance()
                .getRegenFactory()
                .scheduleRegen(
                        block,
                        delaySeconds,
                        TimeUnit.SECONDS,
                        regeneratorID,
                        originalMaterial,
                        originalBlockData
                );
    }
}