package io.nexstudios.nexregen.service.template;

import io.nexstudios.nexregen.service.manager.RegenManager;
import io.nexstudios.serviceregistry.di.Dependencies;
import io.nexstudios.serviceregistry.di.Service;
import io.nexstudios.serviceregistry.di.ServiceAccessor;
import org.bukkit.block.Block;
import org.bukkit.block.data.Ageable;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.util.*;

@Dependencies({
    RegenManager.class
})
public final class RegenTemplateService implements Service {

  private final RegenManager regen;

  private static volatile Set<String> cachedTemplateIds = Set.of();
  private static volatile long cachedLastModified = -1L;

  public RegenTemplateService(ServiceAccessor accessor) {
    this.regen = accessor.getService(RegenManager.class);
    refreshCacheIfNeeded();
  }

  public Set<String> templateIds() {
    refreshCacheIfNeeded();
    return cachedTemplateIds;
  }

  public static Set<String> cachedTemplateIds() {
    return cachedTemplateIds;
  }

  public void reload() {
    cachedLastModified = Long.MIN_VALUE;
    refreshCacheIfNeeded();
  }

  public Optional<ConfigurationSection> getTemplateSection(String templateId) {
    Objects.requireNonNull(templateId, "templateId");
    refreshCacheIfNeeded();

    YamlConfiguration cfg = loadTemplatesConfig();
    if (cfg == null) return Optional.empty();

    return Optional.ofNullable(cfg.getConfigurationSection("templates." + templateId));
  }

  public Map<String, Object> buildEntryFromTemplate(Block target, String templateId, String regenTimeSpec) {
    Objects.requireNonNull(target, "target");
    Objects.requireNonNull(templateId, "templateId");
    Objects.requireNonNull(regenTimeSpec, "regenTimeSpec");

    ConfigurationSection template = getTemplateSection(templateId)
        .orElseThrow(() -> new IllegalArgumentException("Unknown template id: " + templateId));

    return buildEntryFromTemplate(target, template, regenTimeSpec);
  }

  public File templatesFile() {
    return new File(regen.plugin().getDataFolder(), "templates.yml");
  }

  private YamlConfiguration loadTemplatesConfig() {
    File f = templatesFile();
    if (!f.exists()) return null;
    return YamlConfiguration.loadConfiguration(f);
  }

  private void refreshCacheIfNeeded() {
    File f = templatesFile();
    long lm = f.exists() ? f.lastModified() : -1L;

    if (lm == cachedLastModified) {
      return;
    }

    cachedLastModified = lm;

    if (!f.exists()) {
      cachedTemplateIds = Set.of();
      return;
    }

    YamlConfiguration cfg = YamlConfiguration.loadConfiguration(f);
    ConfigurationSection sec = cfg.getConfigurationSection("templates");
    if (sec == null) {
      cachedTemplateIds = Set.of();
      return;
    }

    cachedTemplateIds = Collections.unmodifiableSet(new LinkedHashSet<>(sec.getKeys(false)));
  }

  private static Map<String, Object> buildEntryFromTemplate(Block target, ConfigurationSection template, String regenTimeSpec) {
    Map<String, Object> entry = new LinkedHashMap<>();

    String blockSpec = "minecraft:" + target.getType().getKey().getKey();
    entry.put("block", blockSpec);

    boolean detectMaxAge = template.getBoolean("detect-max-age", false);
    if (detectMaxAge && target.getBlockData() instanceof Ageable ageable) {
      entry.put("age", ageable.getMaximumAge());
    }

    entry.put("regen-time", regenTimeSpec);

    Object breakConds = template.get("break-conditions");
    if (breakConds != null) {
      entry.put("break-conditions", deepCopyYamlValue(breakConds));
    }

    ConfigurationSection settings = template.getConfigurationSection("settings");
    if (settings != null) {
      Map<String, Object> settingsOut = new LinkedHashMap<>();
      settingsOut.put("drop-items", settings.getBoolean("drop-items", false));
      settingsOut.put("drop-xp", settings.getBoolean("drop-xp", false));
      if (settings.contains("replace-only-bottom")) {
        settingsOut.put("replace-only-bottom", settings.getBoolean("replace-only-bottom", false));
      }

      ConfigurationSection repl = settings.getConfigurationSection("replacement");
      if (repl != null) {
        Map<String, Object> replOut = new LinkedHashMap<>();

        boolean useBaseCrop = repl.getBoolean("use-base-crop", false);
        boolean sameBlockType = repl.getBoolean("same-block-type", false);

        if (useBaseCrop || sameBlockType) {
          replOut.put("block", blockSpec);
        } else if (repl.contains("block")) {
          replOut.put("block", repl.getString("block"));
        } else {
          replOut.put("block", blockSpec);
        }

        if (repl.contains("age")) {
          replOut.put("age", repl.getInt("age"));
        }

        settingsOut.put("replacement", replOut);
      }

      entry.put("settings", settingsOut);
    }

    Object maybeSettings = entry.get("settings");
    if (maybeSettings instanceof Map<?, ?> m) {
      Object repl = m.get("replacement");
      if (repl != null) {
        entry.put("replacement", deepCopyYamlValue(repl));
      }
      m.remove("replacement");
    }

    return entry;
  }

  private static Object deepCopyYamlValue(Object v) {
    switch (v) {
      case null -> {
        return null;
      }
      case Map<?, ?> m -> {
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<?, ?> e : m.entrySet()) {
          out.put(String.valueOf(e.getKey()), deepCopyYamlValue(e.getValue()));
        }
        return out;
      }
      case List<?> l -> {
        List<Object> out = new ArrayList<>(l.size());
        for (Object o : l) out.add(deepCopyYamlValue(o));
        return out;
      }
      default -> {
        return v;
      }
    }
  }
}