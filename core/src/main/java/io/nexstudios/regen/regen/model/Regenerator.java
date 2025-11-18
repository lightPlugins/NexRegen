package io.nexstudios.regen.regen.model;

import lombok.Getter;
import lombok.Setter;
import org.bukkit.Material;

import java.util.List;
import java.util.Map;

@Getter
@Setter
public class Regenerator {

    private String id;
    private RegenConditions conditions;
    private List<RegenBlock> regenBlocks;

    @Getter
    @Setter
    public static class RegenConditions {
        private List<Map<String, Object>> conditions;
    }

    @Getter
    @Setter
    public static class RegenBlock {
        private String block;
        private String regenTime;
        private List<Map<String, Object>> breakConditions;
        private RegenBlockSettings settings;
        private RegenReplacement replacement;

        /**
         * Vorparstes Material des zu brechenden Blocks (aus "block").
         */
        private Material blockMaterial;

        /**
         * Vorparstes Material des Replacement-Blocks (aus "replacement.block").
         */
        private Material replacementMaterial;
    }

    @Getter
    @Setter
    public static class RegenBlockSettings {
        private boolean applyPhysics;
        private boolean dropItems;
        private boolean autoPickup;
        private boolean dropXp;
    }

    @Getter
    @Setter
    public static class RegenReplacement {
        private String block;
    }
}