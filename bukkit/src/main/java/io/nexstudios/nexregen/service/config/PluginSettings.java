package io.nexstudios.nexregen.service.config;

import org.bukkit.Material;

import java.util.Collections;
import java.util.Set;

public record PluginSettings(
    boolean blockInteractionBlockingEnabled,
    boolean allowEntityInteraction,
    Set<Material> allowedBlocks
) {
  public static PluginSettings defaults() {
    return new PluginSettings(true, true, Collections.emptySet());
  }

  public boolean isBlockAllowed(Material material) {
    if (material == null) return false;
    return allowedBlocks.contains(material);
  }
}


