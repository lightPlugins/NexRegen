package io.nexstudios.regen.regen;

import io.nexstudios.nexus.bukkit.utils.NexusLogger;
import io.nexstudios.regen.NexRegen;
import io.nexstudios.regen.regen.model.Regenerator;
import lombok.Getter;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.util.*;
import java.util.stream.Collectors;

public class RegenReader {

    private final List<File> files;
    private NexusLogger logger;

    @Getter
    private final Map<String, Regenerator> regenMap;

    /**
     * Index: welches Material triggert welche Regeneratoren / RegenBlocks.
     * Wird beim Einlesen aufgebaut und dann im Event-Handler genutzt.
     */
    @Getter
    private final Map<Material, List<IndexedRegenBlock>> blocksByMaterial = new EnumMap<>(Material.class);

    public RegenReader(List<File> files, NexusLogger logger) {
        this.files = files;
        this.logger = logger;
        this.regenMap = new HashMap<>();
    }

    public void read() {
        regenMap.clear();
        blocksByMaterial.clear();

        for (File file : files) {

            YamlConfiguration config = YamlConfiguration.loadConfiguration(file);
            Regenerator regenerator = new Regenerator();

            String id = file.getName().replace(".yml", "");
            regenerator.setId(id);

            Regenerator.RegenConditions conditions = new Regenerator.RegenConditions();
            conditions.setConditions(readConditions(config, regenerator));
            regenerator.setConditions(conditions);

            List<Regenerator.RegenBlock> regenBlocks = readRegenBlock(config, regenerator);

            if (regenBlocks == null || regenBlocks.isEmpty()) {
                logger.warning("No valid regen blocks found for regen file " + regenerator.getId() + ".yml -> Skipping regenerator.");
                continue;
            }

            regenerator.setRegenBlocks(regenBlocks);
            regenMap.put(id, regenerator);

            // Index nach Material aufbauen
            for (Regenerator.RegenBlock block : regenBlocks) {
                Material mat = block.getBlockMaterial();
                if (mat == null) {
                    continue;
                }
                blocksByMaterial
                        .computeIfAbsent(mat, m -> new ArrayList<>())
                        .add(new IndexedRegenBlock(id, regenerator, block));
            }
        }

        logger.info("Successfully read <green>" + regenMap.size() + "<reset> regen files.");
    }

    /**
     * Ein Eintrag im Material-Index: referenziert Regenerator und den konkreten RegenBlock.
     */
    @Getter
    public static class IndexedRegenBlock {
        private final String regeneratorId;
        private final Regenerator regenerator;
        private final Regenerator.RegenBlock regenBlock;

        public IndexedRegenBlock(String regeneratorId, Regenerator regenerator, Regenerator.RegenBlock regenBlock) {
            this.regeneratorId = regeneratorId;
            this.regenerator = regenerator;
            this.regenBlock = regenBlock;
        }
    }

    @SuppressWarnings("unchecked")
    private List<Regenerator.RegenBlock> readRegenBlock(YamlConfiguration config, Regenerator regenerator) {

        List<Map<?, ?>> regenConfig = config.getMapList("regen");

        if (regenConfig.isEmpty()) {
            logger.error("Could not find 'regen' section in regen file " + regenerator.getId() + ".yml. -> Skipping.");
            return Collections.emptyList();
        }

        List<Regenerator.RegenBlock> regenBlocks = new ArrayList<>();

        for (Map<?, ?> regenDataRaw : regenConfig) {

            if (regenDataRaw == null) {
                logger.error("Invalid regen data format for regen file " + regenerator.getId() + ".yml -> Skipping entry.");
                continue;
            }

            Map<String, Object> regenData;
            try {
                regenData = (Map<String, Object>) regenDataRaw;
            } catch (ClassCastException ex) {
                logger.error("Invalid regen data type in regen file " + regenerator.getId() + ".yml -> Skipping entry.");
                continue;
            }

            Regenerator.RegenBlock regenBlock = new Regenerator.RegenBlock();

            try {

                String block = (String) regenData.get("block");
                String regenTime = (String) regenData.get("regen-time");

                if (block == null || block.isEmpty()) {
                    logger.error("Missing 'block' in regen entry for file " + regenerator.getId() + ".yml -> Skipping entry.");
                    continue;
                }
                if (regenTime == null || regenTime.isEmpty()) {
                    logger.error("Missing 'regen-time' in regen entry for file " + regenerator.getId() + ".yml -> Skipping entry.");
                    continue;
                }

                List<Map<String, Object>> breakConditions = new ArrayList<>();

                List<Map<String, Object>> conditionsConfigs =
                        (List<Map<String, Object>>) regenData.get("break-conditions");
                if (conditionsConfigs != null) {
                    for (Map<String, Object> conditionConfig : conditionsConfigs) {
                        if (conditionConfig == null) {
                            continue;
                        }
                        if (conditionConfig.containsKey("id")) {
                            breakConditions.add(conditionConfig);
                        } else {
                            logger.warning("Condition skipped in regen file " + regenerator.getId() + ".yml -> Missing 'id'");
                        }
                    }
                } else {
                    logger.warning("Condition section is null in regen file " + regenerator.getId() + ".yml ->  Skipping conditions");
                }

                regenBlock.setBlock(block);
                regenBlock.setRegenTime(regenTime);
                regenBlock.setBreakConditions(breakConditions);

                // SETTINGS als künstliche Section aus der Map bauen
                Map<?, ?> rawSettings = (Map<?, ?>) regenData.get("settings");
                ConfigurationSection settingsSection = null;
                if (rawSettings instanceof Map) {
                    settingsSection = config.createSection(
                            "settings-temp-" + UUID.randomUUID(),
                            (Map<?, ?>) rawSettings
                    );
                }

                Regenerator.RegenBlockSettings settings = readRegenBlockSettings(settingsSection, regenerator);
                if (settings == null) {
                    logger.error("Invalid settings in regen file " + regenerator.getId() + ".yml -> Skipping entry.");
                    continue;
                }
                regenBlock.setSettings(settings);

                // Replacement
                Map<?, ?> rawReplacement = (Map<?, ?>) regenData.get("replacement");
                ConfigurationSection replacementSection = null;
                if (rawReplacement != null) {
                    replacementSection = config.createSection(
                            "replacement-temp-" + UUID.randomUUID(),
                            (Map<?, ?>) rawReplacement
                    );
                }

                Regenerator.RegenReplacement replacement = readRegenReplacement(replacementSection, regenerator);
                if (replacement == null || replacement.getBlock() == null || replacement.getBlock().isEmpty()) {
                    logger.error("Invalid or missing replacement in regen file " + regenerator.getId() + ".yml -> Skipping entry.");
                    continue;
                }
                regenBlock.setReplacement(replacement);

                // Material aus "block"
                Material blockMaterial = parseNamespacedBlockMaterial(
                        regenBlock.getBlock(),
                        regenerator.getId(),
                        "block"
                );
                if (blockMaterial == null || !blockMaterial.isBlock()) {
                    logger.error("Regen Block in Regenerator file " + regenerator.getId() + ".yml is not a valid block! -> Skipping entry.");
                    continue;
                }
                regenBlock.setBlockMaterial(blockMaterial);

                // Material aus "replacement.block"
                Material replacementMaterial = parseNamespacedBlockMaterial(
                        regenBlock.getReplacement().getBlock(),
                        regenerator.getId(),
                        "replacement.block"
                );
                if (replacementMaterial == null || !replacementMaterial.isBlock()) {
                    logger.error("Replacement Block in Regenerator file " + regenerator.getId() + ".yml is not a valid block! -> Skipping entry.");
                    continue;
                }
                regenBlock.setReplacementMaterial(replacementMaterial);

                regenBlocks.add(regenBlock);

            } catch (Exception e) {
                logger.error("Invalid regen data format for regen file " + regenerator.getId() + ".yml -> Skipping entry");
            }
        }

        if (regenBlocks.isEmpty()) {
            return Collections.emptyList();
        }

        return regenBlocks;
    }

    private Material parseNamespacedBlockMaterial(String value, String regenId, String fieldPath) {

        String[] blockProperties = value.split(" ");
        if (blockProperties.length == 0) {
            logger.error(List.of(
                    "Could not parse " + fieldPath + " in regen file " + regenId + ".yml",
                    "Empty block value: '" + value + "'"
            ));
            return null;
        }

        String[] blockMaterial = blockProperties[0].split(":");
        if (blockMaterial.length != 2) {
            logger.error(List.of(
                    "Could not parse " + fieldPath + " in regen file " + regenId + ".yml",
                    "Invalid block params: '" + value + "'"
            ));
            return null;
        }

        if (!blockMaterial[0].contains("minecraft")) {
            logger.error(List.of(
                    "Could not parse " + fieldPath + " in regen file " + regenId + ".yml",
                    "Invalid block namespace: '" + blockMaterial[0] + "'"
            ));
        }

        try {
            return Material.valueOf(blockMaterial[1].toUpperCase());
        } catch (IllegalArgumentException ex) {
            logger.error(List.of(
                    "Could not parse " + fieldPath + " in regen file " + regenId + ".yml",
                    "Unknown material: '" + blockMaterial[1] + "'"
            ));
            return null;
        }
    }

    private Regenerator.RegenReplacement readRegenReplacement(ConfigurationSection replacementSection, Regenerator regenerator) {

        if (replacementSection == null) {
            logger.error(List.of(
                    "Could not load regen file " + regenerator.getId() + ".yml",
                    "Could not find replacement section 'replacement'"
            ));
            return null;
        }

        String block = replacementSection.getString("block");

        if (block == null) {
            logger.error(List.of(
                    "Could not load regen file " + regenerator.getId() + ".yml",
                    "Could not find replacement block in section 'replacement'"
            ));
            return null;
        }

        Regenerator.RegenReplacement replacement = new Regenerator.RegenReplacement();
        replacement.setBlock(block);

        return replacement;
    }

    private Regenerator.RegenBlockSettings readRegenBlockSettings(ConfigurationSection settingsSection,
                                                                  Regenerator regenerator) {

        Regenerator.RegenBlockSettings settings = new Regenerator.RegenBlockSettings();

        try {
            boolean applyPhysics = true;
            boolean dropItems = true;
            boolean autoPickup = true;
            boolean dropXp = true;

            if (settingsSection != null) {
                applyPhysics = settingsSection.getBoolean("apply-physics", false);
                dropItems = settingsSection.getBoolean("drop-items", true);
                autoPickup = settingsSection.getBoolean("auto-pickup", false);
                dropXp = settingsSection.getBoolean("drop-xp", true);
            }

            settings.setApplyPhysics(applyPhysics);
            settings.setDropItems(dropItems);
            settings.setAutoPickup(autoPickup);
            settings.setDropXp(dropXp);

        } catch (Exception e) {
            logger.error("Invalid regen data format for regen file " + regenerator.getId() + ".yml -> Skipping entry");
            return null;
        }

        return settings;
    }

    private List<Map<String, Object>> readConditions(YamlConfiguration config, Regenerator regenerator) {

        List<Map<?, ?>> rawConditions = config.getMapList("enable-conditions");

        if (rawConditions.isEmpty()) {
            return List.of();
        }

        List<Map<String, Object>> validConditions = new ArrayList<>();

        for (Map<?, ?> rawCondition : rawConditions) {
            if (rawCondition == null) {
                continue;
            }

            Object idValue = rawCondition.get("id");
            if (!(idValue instanceof String)) {
                logger.warning("Condition skipped in regen file " + regenerator.getId() + ".yml Missing or invalid 'id'.");
                continue;
            }

            @SuppressWarnings("unchecked")
            Map<String, Object> conditionConfig = (Map<String, Object>) rawCondition;

            validConditions.add(conditionConfig);
        }

        return validConditions;
    }
}