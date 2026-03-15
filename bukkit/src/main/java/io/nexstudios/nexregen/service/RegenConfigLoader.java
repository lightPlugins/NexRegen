package io.nexstudios.nexregen.service;

import io.nexstudios.configservice.config.ConfigurationSection;
import io.nexstudios.configservice.config.FileConfiguration;
import io.nexstudios.configservice.service.multireader.MultiFileReaderService;
import io.nexstudios.nexregen.service.model.RegenEntry;
import io.nexstudios.nexregen.service.model.RegenSettings;
import io.nexstudios.nexregen.service.util.BlockDataSpec;
import io.nexstudios.serviceregistry.di.Service;
import io.nexstudios.serviceregistry.di.ServiceAccessor;
import org.bukkit.Material;

import java.nio.file.Path;
import java.util.*;
import java.util.OptionalInt;

public final class RegenConfigLoader implements Service {

  private final MultiFileReaderService multiFileReader;

  public RegenConfigLoader(ServiceAccessor services) {
    this.multiFileReader = services.getService(MultiFileReaderService.class);
  }

  public List<RegenEntry> loadAll() {
    Map<Path, FileConfiguration> files = multiFileReader.loadAll(Path.of("blocks"));

    List<RegenEntry> out = new ArrayList<>();
    for (Map.Entry<Path, FileConfiguration> e : files.entrySet()) {
      Path rel = e.getKey();
      FileConfiguration cfg = e.getValue();

      if (!cfg.getBoolean("enable", true)) continue;

      List<ConfigurationSection> globalConditions = cfg.getSectionList("global-conditions");

      for (ConfigurationSection regenSec : cfg.getSectionList("regen")) {
        out.add(parseEntry(globalConditions, regenSec, rel.toString()));
      }
    }

    return List.copyOf(out);
  }

  private RegenEntry parseEntry(List<ConfigurationSection> globalConditions, ConfigurationSection sec, String fileName) {
    String blockSpecRaw = sec.getString("block", "").trim();
    if (blockSpecRaw.isEmpty()) {
      throw new IllegalStateException("Missing 'block' in regen entry (" + fileName + ")");
    }

    Material matchMaterial = BlockDataSpec.toMaterial(blockSpecRaw)
        .orElseThrow(() -> new IllegalStateException("Invalid material in 'block': " + blockSpecRaw + " (" + fileName + ")"));

    OptionalInt age = optionalInt(sec, "age");

    String regenTime = sec.getString("regen-time", "3").trim();

    List<ConfigurationSection> breakConditions = sec.getSectionList("break-conditions");

    ConfigurationSection settingsSec = sec.getSection("settings");
    RegenSettings settings = new RegenSettings(
        settingsSec != null && settingsSec.getBoolean("apply-physics", false),
        settingsSec != null && settingsSec.getBoolean("drop-items", false),
        settingsSec != null && settingsSec.getBoolean("drop-xp", false)
    );

    ConfigurationSection repl = sec.getSection("replacement");
    String replacementSpecRaw = repl != null ? repl.getString("block", blockSpecRaw) : blockSpecRaw;
    OptionalInt replacementAge = repl != null ? optionalInt(repl, "age") : OptionalInt.empty();

    // IMPORTANT: age in specs is ignored; age only comes from separate fields.
    String finalSpecNoAge = BlockDataSpec.normalize(blockSpecRaw);       // normalized but may still contain age in brackets
    String replacementSpecNoAge = BlockDataSpec.normalize(replacementSpecRaw);

    // store normalized; RegenManager will parse "without age" anyway (double-safe).
    return new RegenEntry(
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
    );
  }

  private static OptionalInt optionalInt(ConfigurationSection sec, String path) {
    if (sec == null) return OptionalInt.empty();
    if (!sec.contains(path)) return OptionalInt.empty();
    return OptionalInt.of(sec.getInt(path, 0));
  }
}