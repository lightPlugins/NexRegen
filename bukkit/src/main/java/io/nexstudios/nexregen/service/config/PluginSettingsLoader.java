package io.nexstudios.nexregen.service.config;

import io.nexstudios.configservice.config.FileConfiguration;
import io.nexstudios.configservice.service.singlereader.FileReaderService;
import io.nexstudios.serviceregistry.di.Dependencies;
import io.nexstudios.serviceregistry.di.Service;
import io.nexstudios.serviceregistry.di.ServiceAccessor;
import org.bukkit.Material;

import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Dependencies({
    FileReaderService.class
})
public final class PluginSettingsLoader implements Service {

  private final FileReaderService fileReader;

  public PluginSettingsLoader(ServiceAccessor services) {
    this.fileReader = services.getService(FileReaderService.class);
  }

  public PluginSettings loadSettings() {
    FileConfiguration settings = fileReader.load(Path.of("settings.yml"), "settings.yml", true);
    
    if (settings == null) {
      return PluginSettings.defaults();
    }

    return parseSettings(settings);
  }

  public PluginSettings reloadSettings() {
    return loadSettings();
  }

  private PluginSettings parseSettings(FileConfiguration settings) {

    boolean blockingEnabled = settings.getBoolean("block-interaction-blocking.enabled", true);
    boolean allowEntityInteraction = settings.getBoolean("block-interaction-blocking.allow-entity-interaction", true);

    Set<Material> allowedBlocks = new HashSet<>();
    List<String> blockList = settings.getStringList("block-interaction-blocking.allowed-blocks");
    if (blockList != null) {
      for (String blockName : blockList) {
        if (blockName == null || blockName.isBlank()) continue;
        try {
          Material mat = Material.matchMaterial(blockName);
          if (mat != null) {
            allowedBlocks.add(mat);
          }
        } catch (Exception ignored) {
        }
      }
    }

    return new PluginSettings(blockingEnabled, allowEntityInteraction, allowedBlocks);
  }
}




