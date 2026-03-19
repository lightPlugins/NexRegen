package io.nexstudios.nexregen.service.model;

import io.nexstudios.configservice.config.ConfigurationSection;
import org.bukkit.Material;

import java.util.List;
import java.util.OptionalInt;

public record RegenEntry(
    Material matchMaterial,
    OptionalInt matchAge,
    OptionalInt finalAge,
    OptionalInt replacementAge,
    String regenTimeSpec,
    List<ConfigurationSection> globalConditions,
    List<ConfigurationSection> breakConditions,
    RegenSettings settings,
    String replacementBlockDataSpec,
    String finalBlockDataSpec,
    List<BreakKey>breakKeys
) {
}