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
import org.bukkit.entity.Player;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.inventory.ItemStack;

import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

public class AgeableRegen implements NexusRegen {

    @Override
    public void handle(BlockBreakEvent event) {

        Material type = event.getBlock().getType();

        // Nur explizite Ageable-Crops behandeln
        switch (type) {
            case WHEAT, CARROTS, POTATOES, BEETROOTS, SWEET_BERRY_BUSH:
                handleRegen(event);
                return;
            default:
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
                        "Could not handle ageable block break event for block " + block.getType() + " at " + block.getLocation(),
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
                        "Could not handle ageable block break event for block " + block.getType() + " at " + block.getLocation(),
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
                        "Could not handle ageable block break event for block " + block.getType() + " at " + block.getLocation(),
                        "Replacement block in Regenerator file " + regenID + ".yml is not a valid block!"
                ));
                continue;
            }

            // Aktuelles Age des Blocks holen
            BlockData currentData = block.getBlockData();
            int currentAge = -1;
            if (currentData instanceof Ageable ageableCurrent) {
                currentAge = ageableCurrent.getAge();
            }

            // Gewünschtes Age aus der Config ("block"-String) parsen, z. B. "minecraft:wheat age:7"
            int requiredAge = -1;
            String blockConfig = regenBlock.getBlock(); // z. B. minecraft:wheat age:7
            String[] blockParts = blockConfig.split(" ");
            for (String part : blockParts) {
                part = part.trim();
                if (part.startsWith("age:")) {
                    String value = part.substring("age:".length());
                    try {
                        requiredAge = Integer.parseInt(value);
                    } catch (NumberFormatException ex) {
                        NexRegen.nexusLogger.error(List.of(
                                "Failed to parse required age for AgeableRegen block.",
                                "Regenerator: " + regenID,
                                "Value: " + blockConfig,
                                "Age token: " + part
                        ));
                    }
                    break;
                }
            }

            // Wenn ein requiredAge definiert ist und der Block dieses Age nicht hat:
            // Event CANCELN -> keine Drops, kein Regen, verhindert den Abbau von age 0 / halbreifen Crops.
            if (requiredAge >= 0 && currentAge >= 0 && currentAge != requiredAge) {
                event.setCancelled(true);
                return;
            }

            // Drops steuern
            event.setDropItems(regenBlock.getSettings().isDropItems());

            if (regenBlock.getSettings().isDropItems()) {
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

        String originalMaterial = block.getType().name();
        String originalBlockData = block.getBlockData().getAsString();

        long delaySeconds = NexRegen.getInstance()
                .getRegenFactory()
                .parseRegenTimeToSeconds(regenBlock.getRegenTime());

        // Replacement-Konfiguration, z. B. "minecraft:wheat age:0"
        String replacementBlockString = regenBlock.getReplacement().getBlock();

        // Age aus dem String extrahieren
        int ageTmp = 0;
        String[] parts = replacementBlockString.split(" ");
        for (String part : parts) {
            part = part.trim();
            if (part.startsWith("age:")) {
                String value = part.substring("age:".length());
                try {
                    ageTmp = Integer.parseInt(value);
                } catch (NumberFormatException ex) {
                    NexRegen.nexusLogger.error(List.of(
                            "Failed to parse age for AgeableRegen replacement.",
                            "Regenerator: " + regeneratorID,
                            "Value: " + replacementBlockString,
                            "Age token: " + part
                    ));
                }
                break;
            }
        }
        final int replacementAge = ageTmp;
        final String finalOriginalMaterial = originalMaterial;

        // Block über der Pflanze merken, damit wir "Abploppen" verhindern können
        Block above = block.getRelative(0, 1, 0);
        final Material aboveTypeBefore = above.getType();
        final BlockData aboveDataBefore =
                aboveTypeBefore != Material.AIR ? above.getBlockData().clone() : null;

        // Replacement im nächsten Tick setzen
        Bukkit.getScheduler().runTask(NexRegen.getInstance(), () -> {
            Block b = event.getBlock();

            // Wenn der Block inzwischen AIR ist, setzen wir unseren Saatzustand
            if (b.getType() == Material.AIR) {
                BlockData replacementData = Bukkit.createBlockData(brokenTypeToMaterial(finalOriginalMaterial));

                if (replacementData instanceof Ageable ageable) {
                    int max = ageable.getMaximumAge();
                    int clamped = Math.max(0, Math.min(max, replacementAge));
                    ageable.setAge(clamped);
                }

                boolean applyPhysics = regenBlock.getSettings().isApplyPhysics();
                b.setBlockData(replacementData, applyPhysics);
            }

            // Block über der Pflanze nach Physics wiederherstellen,
            // falls er durch unser Setzen "abgeploppt" ist.
            Block aboveNow = b.getRelative(0, 1, 0);
            if (aboveTypeBefore != Material.AIR) {
                if (aboveNow.getType() == Material.AIR) {
                    aboveNow.setType(aboveTypeBefore, false);
                    if (aboveDataBefore != null) {
                        aboveNow.setBlockData(aboveDataBefore, false);
                    }
                }
            }
        });

        // Zielzustand = ursprünglicher Zustand beim Abbau (z. B. age=7)
        NexRegen.getInstance()
                .getRegenFactory()
                .scheduleRegen(
                        block,
                        delaySeconds,
                        TimeUnit.SECONDS,
                        regeneratorID,
                        finalOriginalMaterial,
                        originalBlockData,
                        finalOriginalMaterial,
                        originalBlockData
                );
    }

    private Material brokenTypeToMaterial(String originalMaterial) {
        try {
            return Material.valueOf(originalMaterial);
        } catch (IllegalArgumentException ex) {
            return Material.AIR;
        }
    }
}