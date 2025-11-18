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

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

public class SugarCaneRegen implements NexusRegen {

    /**
     * Locations von Sugar-Cane-Blöcken, die gerade durch player.breakBlock(...)
     * abgebaut werden und für die KEIN weiterer Regen geplant werden soll.
     */
    private static final Set<String> IGNORE_LOCATIONS = ConcurrentHashMap.newKeySet();

    /**
     * Locations von Sugar-Cane-Blöcken, für die bereits ein Regen geplant ist.
     * Wert = regenAt (Millis). Wird genutzt, um doppelte Planungen zu verhindern.
     */
    private static final ConcurrentHashMap<String, Long> SCHEDULED = new ConcurrentHashMap<>();

    private String key(Block block) {
        Location loc = block.getLocation();
        return loc.getWorld().getName() + ":" + loc.getBlockX() + ":" + loc.getBlockY() + ":" + loc.getBlockZ();
    }

    @Override
    public void handle(BlockBreakEvent event) {

        Block block = event.getBlock();

        // Nur Zuckerrohr behandeln
        if (block.getType() != Material.SUGAR_CANE) {
            return;
        }

        String locKey = key(block);

        // synthetischer Break (von player.breakBlock)
        boolean syntheticBreak = IGNORE_LOCATIONS.remove(locKey);

        long now = System.currentTimeMillis();
        Long scheduledAt = SCHEDULED.get(locKey);
        if (scheduledAt != null && scheduledAt > now) {
            // Regen noch pending -> keine neue Planung, aber Drops/XP steuern
            handleRegen(event, syntheticBreak, true);
            return;
        } else if (scheduledAt != null) {
            // alter Eintrag aufräumen
            SCHEDULED.remove(locKey);
        }

        handleRegen(event, syntheticBreak, false);
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
                        "Could not handle sugar cane block break event for block " + block.getType() + " at " + block.getLocation(),
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
                        "Could not handle sugar cane block break event for block " + block.getType() + " at " + block.getLocation(),
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
                        "Could not handle sugar cane block break event for block " + block.getType() + " at " + block.getLocation(),
                        "Replacement block in Regenerator file " + regenID + ".yml is not a valid block!"
                ));
                continue;
            }

            // =======================
            // Drops wie DefaultRegen
            // =======================
            event.setDropItems(false); // Vanilla-Drops immer aus

            if (regenBlock.getSettings().isDropItems()) {
                // leichter Offset
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

            // Für synthetische Breaks nur Drops/XP steuern, keine Säulen-Logik
            if (syntheticBreak || skipColumnLogic) {
                return;
            }

            // =======================
            // Säulen-Regen nur für den ersten echten Break
            // =======================

            // komplette Säule ermitteln (unten/oben)
            List<Block> column = collectSugarCaneColumn(block);

            // Regen für die gesamte Säule planen
            scheduleColumnRegen(column, regenBlock, regenID);

            // alle anderen Blöcke der Säule mit player.breakBlock abbauen
            breakWholeColumnWithPlayer(column, block, player, regenBlock);

            // Event NICHT canceln – Skill-Plugins sehen alle BlockBreakEvents
            return;
        }
    }

    /**
     * Sammelt die gesamte Zuckerrohr-Säule (zusammenhängende SUGAR_CANE-Blöcke)
     * nach unten und oben relativ zum gebrochenen Block.
     */
    private List<Block> collectSugarCaneColumn(Block origin) {
        List<Block> column = new ArrayList<>();
        World world = origin.getWorld();
        int minY = world.getMinHeight();
        int maxY = world.getMaxHeight() - 1;

        // nach unten bis zum ersten Nicht-SugarCane-Block
        Block base = origin;
        while (base.getY() > minY) {
            Block below = base.getRelative(0, -1, 0);
            if (below.getType() != Material.SUGAR_CANE) {
                break;
            }
            base = below;
        }

        // von base nach oben bis zum letzten SugarCane-Block
        Block current = base;
        while (current.getY() <= maxY && current.getType() == Material.SUGAR_CANE) {
            column.add(current);
            current = current.getRelative(0, 1, 0);
        }

        return column;
    }

    /**
     * Regen für alle Blöcke der Säule einplanen – alle kommen nach der Delay-Zeit
     * gleichzeitig zurück. Alle betroffenen Locations werden in SCHEDULED markiert.
     */
    private void scheduleColumnRegen(List<Block> column,
                                     Regenerator.RegenBlock regenBlock,
                                     String regeneratorID) {

        long delaySeconds = NexRegen.getInstance()
                .getRegenFactory()
                .parseRegenTimeToSeconds(regenBlock.getRegenTime());

        long regenAt = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(delaySeconds);

        for (Block caneBlock : column) {

            String originalMaterial = Material.SUGAR_CANE.name();
            String originalBlockData = Bukkit.createBlockData(Material.SUGAR_CANE).getAsString();
            String locKey = key(caneBlock);

            // Markieren, dass für diese Location bereits ein Regen geplant ist
            SCHEDULED.put(locKey, regenAt);

            NexRegen.getInstance()
                    .getRegenFactory()
                    .scheduleRegen(
                            caneBlock,
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
     * Dadurch gibt es genau so viele BlockBreakEvents (und XP) wie Sugar-Cane-Blöcke.
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

            Block caneBlock = column.get(i);

            // IMMER über player.breakBlock abbauen, unabhängig von drop-items,
            // damit pro Block ein BlockBreakEvent (und damit XP) ausgelöst wird.
            IGNORE_LOCATIONS.add(key(caneBlock));

            boolean success = player.breakBlock(caneBlock);
            if (!success) {
                IGNORE_LOCATIONS.remove(key(caneBlock));
                caneBlock.setType(regenBlock.getReplacementMaterial(), regenBlock.getSettings().isApplyPhysics());
            }
        }
    }
}