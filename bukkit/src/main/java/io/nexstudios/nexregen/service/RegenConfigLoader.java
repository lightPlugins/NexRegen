package io.nexstudios.nexregen.service;

import io.nexstudios.configservice.config.ConfigurationSection;
import io.nexstudios.configservice.config.FileConfiguration;
import io.nexstudios.configservice.service.multireader.MultiFileReaderService;
import io.nexstudios.nexlogic.common.services.logging.LoggerService;
import io.nexstudios.nexregen.service.model.RegenEntry;
import io.nexstudios.nexregen.service.model.RegenSettings;
import io.nexstudios.nexregen.service.util.BlockDataSpec;
import io.nexstudios.serviceregistry.di.Dependencies;
import io.nexstudios.serviceregistry.di.Service;
import io.nexstudios.serviceregistry.di.ServiceAccessor;
import org.bukkit.Material;

import java.nio.file.Path;
import java.util.*;
import java.util.OptionalInt;

@Dependencies({
    MultiFileReaderService.class,
    LoggerService.class
})
public final class RegenConfigLoader implements Service {

  private final MultiFileReaderService multiFileReader;
  private final LoggerService logger;

  public RegenConfigLoader(ServiceAccessor services) {
    this.multiFileReader = services.getService(MultiFileReaderService.class);
    this.logger = services.getService(LoggerService.class);
  }

  public List<RegenEntry> loadAll() {
    Map<Path, FileConfiguration> files = multiFileReader.loadAll(Path.of("blocks"));

    List<RegenEntry> out = new ArrayList<>();
    List<String> errors = new ArrayList<>();
    int totalRegenSections = 0;

    for (Map.Entry<Path, FileConfiguration> e : files.entrySet()) {
      Path rel = e.getKey();
      FileConfiguration cfg = e.getValue();

      // Skip file if disabled (also safe if config implementation throws -> we catch below)
      try {
        if (!cfg.getBoolean("enable", true)) continue;
      } catch (Exception ex) {
        errors.add(formatConfigError(rel.toString(), -1, "Invalid file root", ex));
        continue;
      }

      List<ConfigurationSection> globalConditions;
      List<ConfigurationSection> regenList;
      try {
        globalConditions = cfg.getSectionList("global-conditions");
        regenList = cfg.getSectionList("regen");
      } catch (Exception ex) {
        errors.add(formatConfigError(rel.toString(), -1, "Invalid file structure (expected lists: global-conditions, regen)", ex));
        continue;
      }

      int idx = 0;
      for (ConfigurationSection regenSec : regenList) {
        idx++;
        totalRegenSections++;

        Optional<RegenEntry> parsed = parseEntry(globalConditions, regenSec, rel.toString(), idx, errors);
        parsed.ifPresent(out::add);
      }
    }

    // Only ONE console error for all config problems
    if (!errors.isEmpty()) {
      StringBuilder sb = new StringBuilder();
      sb.append("NexRegen: Configuration errors detected. The plugin will continue running, ")
          .append("but invalid regen entries were skipped.\n")
          .append("Summary: files=").append(files.size())
          .append(", regen-sections=").append(totalRegenSections)
          .append(", loaded-entries=").append(out.size())
          .append(", skipped-entries=").append(errors.size())
          .append("\n")
          .append("Details:\n")
          .append(String.join("\n", errors));

      logger.logger().severe(sb.toString());
    }

    return List.copyOf(out);
  }

  private Optional<RegenEntry> parseEntry(
      List<ConfigurationSection> globalConditions,
      ConfigurationSection sec,
      String fileName,
      int entryIndex,
      List<String> errors
  ) {
    try {
      if (sec == null) {
        errors.add("- file='" + fileName + "', regen[" + entryIndex + "] | error=Invalid entry: entry is null");
        return Optional.empty();
      }

      String blockSpecRaw = sec.getString("block", "").trim();
      if (blockSpecRaw.isEmpty()) {
        errors.add("- file='" + fileName + "', regen[" + entryIndex + "] | error=Missing required key 'block'");
        return Optional.empty();
      }

      Optional<Material> matOpt;
      try {
        matOpt = BlockDataSpec.toMaterial(blockSpecRaw);
      } catch (Exception ex) {
        errors.add(formatConfigError(fileName, entryIndex, "Failed to parse 'block' material: " + blockSpecRaw, ex));
        return Optional.empty();
      }

      if (matOpt.isEmpty()) {
        errors.add("- file='" + fileName + "', regen[" + entryIndex + "] | error=Invalid material in 'block': " + blockSpecRaw);
        return Optional.empty();
      }

      Material matchMaterial = matOpt.get();

      OptionalInt age = optionalInt(sec, "age");

      String regenTime = sec.getString("regen-time", "3").trim();
      if (regenTime.isEmpty()) {
        errors.add("- file='" + fileName + "', regen[" + entryIndex + "] | error=Missing required key 'regen-time'");
        return Optional.empty();
      }

      List<ConfigurationSection> breakConditions = sec.getSectionList("break-conditions");

      ConfigurationSection settingsSec = sec.getSection("settings");
      RegenSettings settings = new RegenSettings(
          settingsSec != null && settingsSec.getBoolean("drop-items", false),
          settingsSec != null && settingsSec.getBoolean("drop-xp", false),
          settingsSec != null && settingsSec.getBoolean("replace-only-bottom", false)
      );

      ConfigurationSection repl = sec.getSection("replacement");
      String replacementSpecRaw = repl != null ? repl.getString("block", blockSpecRaw) : blockSpecRaw;
      OptionalInt replacementAge = repl != null ? optionalInt(repl, "age") : OptionalInt.empty();

      String finalSpecNoAge;
      String replacementSpecNoAge;
      try {
        finalSpecNoAge = BlockDataSpec.normalize(blockSpecRaw);
        replacementSpecNoAge = BlockDataSpec.normalize(replacementSpecRaw);
      } catch (Exception ex) {
        errors.add(formatConfigError(fileName, entryIndex, "Failed to normalize block specs", ex));
        return Optional.empty();
      }

      return Optional.of(new RegenEntry(
          matchMaterial,
          age,
          age,
          replacementAge,
          regenTime,
          globalConditions,
          breakConditions,
          settings,
          replacementSpecNoAge,
          finalSpecNoAge
      ));
    } catch (Exception ex) {
      // Catch-all so config never hard-fails the plugin
      errors.add(formatConfigError(fileName, entryIndex, "Unexpected error while parsing regen entry", ex));
      return Optional.empty();
    }
  }

  private static OptionalInt optionalInt(ConfigurationSection sec, String path) {
    if (sec == null) return OptionalInt.empty();
    if (!sec.contains(path)) return OptionalInt.empty();
    return OptionalInt.of(sec.getInt(path, 0));
  }

  private static String formatConfigError(String fileName, int entryIndex, String context, Exception ex) {
    String where = (entryIndex > 0)
        ? ("file='" + fileName + "', regen[" + entryIndex + "]")
        : ("file='" + fileName + "'");

    String type = ex.getClass().getSimpleName();
    String msg = (ex.getMessage() == null || ex.getMessage().isBlank()) ? "(no message)" : ex.getMessage();

    Throwable root = ex;
    while (root.getCause() != null && root.getCause() != root) root = root.getCause();

    String rootType = root.getClass().getSimpleName();
    String rootMsg = (root.getMessage() == null || root.getMessage().isBlank()) ? "(no message)" : root.getMessage();

    return "- " + where + " | context=" + context + " | error=" + type + ": " + msg + " | root=" + rootType + ": " + rootMsg;
  }
}