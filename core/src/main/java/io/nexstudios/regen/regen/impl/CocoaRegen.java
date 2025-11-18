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
import org.bukkit.block.data.Ageable;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Directional;
import org.bukkit.entity.Player;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.inventory.ItemStack;

import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

public class CocoaRegen implements NexusRegen {

    @Override
    public void handle(BlockBreakEvent event) {

        Material type = event.getBlock().getType();

        // Nur Kakao-Blöcke behandeln
        switch (type) {
            case COCOA:
                handleRegen(event);
                return;
            default:
                // andere Blöcke ignorieren
                return;
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
                        "Could not handle cocoa block break event for block " + block.getType() + " at " + block.getLocation(),
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
                        "Could not handle cocoa block break event for block " + block.getType() + " at " + block.getLocation(),
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
                        "Could not handle cocoa block break event for block " + block.getType() + " at " + block.getLocation(),
                        "Replacement block in Regenerator file " + regenID + ".yml is not a valid block!"
                ));
                continue;
            }

            // Aktuelles Age (Reifezustand) der Bohne
            BlockData currentData = block.getBlockData();
            int currentAge = -1;
            if (currentData instanceof Ageable ageableCurrent) {
                currentAge = ageableCurrent.getAge();
            }

            // Gewünschtes Age aus der Config ("block"-String), z. B. "minecraft:cocoa age:2"
            int requiredAge = -1;
            String blockConfig = regenBlock.getBlock(); // z. B. minecraft:cocoa age:2
            String[] blockParts = blockConfig.split(" ");
            for (String part : blockParts) {
                part = part.trim();
                if (part.startsWith("age:")) {
                    String value = part.substring("age:".length());
                    try {
                        requiredAge = Integer.parseInt(value);
                    } catch (NumberFormatException ex) {
                        NexRegen.nexusLogger.error(List.of(
                                "Failed to parse required age for CocoaRegen block.",
                                "Regenerator: " + regenID,
                                "Value: " + blockConfig,
                                "Age token: " + part
                        ));
                    }
                    break;
                }
            }

            // Wenn ein requiredAge definiert ist und der Block dieses Age nicht hat:
            // Event CANCELN -> keine Drops, kein Regen.
            if (requiredAge >= 0 && currentAge >= 0 && currentAge != requiredAge) {
                event.setCancelled(true);
                return;
            }

            // Drops steuern
            event.setDropItems(regenBlock.getSettings().isDropItems());

            if (regenBlock.getSettings().isDropItems()) {
                // kein offset, da cocoa nicht in Blöcken stuck sind
                Location dropLocation = block.getLocation().clone().add(0, 0, 0);

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

        // ursprünglicher Zustand inkl. Facing/Age speichern
        String originalMaterial = block.getType().name();
        String originalBlockData = block.getBlockData().getAsString();

        long delaySeconds = NexRegen.getInstance()
                .getRegenFactory()
                .parseRegenTimeToSeconds(regenBlock.getRegenTime());

        // Event NICHT canceln, damit Skills XP vergeben können
        // event.setCancelled(true); // entfernt

        // Original-BlockData für Facing etc.
        BlockData originalData = block.getBlockData();

        // Replacement-Age aus dem Config-String ziehen, z. B. "minecraft:cocoa age:0"
        String replacementBlockString = regenBlock.getReplacement().getBlock();
        int ageTmp = 0; // Default, falls kein age angegeben

        String[] parts = replacementBlockString.split(" ");
        for (String part : parts) {
            part = part.trim();
            if (part.startsWith("age:")) {
                String value = part.substring("age:".length());
                try {
                    ageTmp = Integer.parseInt(value);
                } catch (NumberFormatException ex) {
                    NexRegen.nexusLogger.error(List.of(
                            "Failed to parse age for CocoaRegen replacement.",
                            "Regenerator: " + regeneratorID,
                            "Value: " + replacementBlockString,
                            "Age token: " + part
                    ));
                }
                break;
            }
        }

        final int replacementAge = ageTmp;
        final BlockData finalOriginalData = originalData;
        final boolean applyPhysics = regenBlock.getSettings().isApplyPhysics();

        // Replacement im nächsten Tick setzen
        Bukkit.getScheduler().runTask(NexRegen.getInstance(), () -> {
            Block b = event.getBlock();
            if (b.getType() == Material.AIR) {
                BlockData replacementData = Bukkit.createBlockData(Material.COCOA);

                // Age + Facing setzen
                if (replacementData instanceof Ageable ageable) {
                    int max = ageable.getMaximumAge();
                    int clamped = Math.max(0, Math.min(max, replacementAge));
                    ageable.setAge(clamped);
                }

                if (finalOriginalData instanceof Directional originalDir &&
                        replacementData instanceof Directional replacementDir) {
                    replacementDir.setFacing(originalDir.getFacing());
                }

                b.setBlockData(replacementData, applyPhysics);
            }
        });

        // Regen einplanen – später wird auf originalen Zustand zurückgesetzt
        NexRegen.getInstance()
                .getRegenFactory()
                .scheduleRegen(
                        block,
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