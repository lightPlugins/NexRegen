package io.nexstudios.nexregen.command;

import io.nexstudios.commandservice.service.commands.annotations.Arg;
import io.nexstudios.commandservice.service.commands.annotations.Command;
import io.nexstudios.commandservice.service.commands.annotations.CommandRoot;
import io.nexstudios.commandservice.service.commands.annotations.Suggest;
import io.nexstudios.commandservice.service.commands.source.NexPaperCommandSource;
import io.nexstudios.languageservice.service.component.ComponentService;
import io.nexstudios.nexregen.command.suggestions.BlockFileSuggestion;
import io.nexstudios.nexregen.command.suggestions.RegenTimeSuggestion;
import io.nexstudios.nexregen.command.suggestions.TemplateIdSuggestion;
import io.nexstudios.nexregen.service.config.RegenConfigLoader;
import io.nexstudios.nexregen.service.manager.RegenManager;
import io.nexstudios.nexregen.service.template.RegenTemplateService;
import io.nexstudios.nexregen.util.RegenTimeParser;
import io.nexstudios.serviceregistry.di.Dependencies;
import io.nexstudios.serviceregistry.di.Service;
import io.nexstudios.serviceregistry.di.ServiceAccessor;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.block.Block;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

import java.io.File;
import java.io.IOException;
import java.util.*;

@CommandRoot(
    name = "nexregen",
    description = "NexRegen admin command"
)
@Dependencies({
    RegenConfigLoader.class,
    RegenManager.class,
    RegenTemplateService.class,
    ComponentService.class
})
public final class RegenAddCommand implements Service {

  private final RegenConfigLoader loader;
  private final RegenManager regen;
  private final RegenTemplateService templateService;
  private final ComponentService componentService;

  public RegenAddCommand(ServiceAccessor accessor) {
    this.loader = accessor.getService(RegenConfigLoader.class);
    this.regen = accessor.getService(RegenManager.class);
    this.templateService = accessor.getService(RegenTemplateService.class);
    this.componentService = accessor.getService(ComponentService.class);
  }

  @Command(value = "add <block-file> <template-id> <regen-time>", permission = "nexregen.admin.add")
  public int add(
      NexPaperCommandSource source,
      @Arg("block-file") @Suggest(BlockFileSuggestion.class) String blockFile,
      @Arg("template-id") @Suggest(TemplateIdSuggestion.class) String templateId,
      @Arg("regen-time") @Suggest(RegenTimeSuggestion.class) String regenTime
  ) {
    Player player = (Player) source.sender();
    if (player == null) return 0;

    String regenTimeSpec = regenTime == null ? "" : regenTime.trim();
    if (regenTimeSpec.isEmpty()) {
      player.sendMessage(componentService.builder(player, "template.missing-regen-time", "", true).build());
      return 0;
    }

    try {
      RegenTimeParser.parseToTicks(regenTimeSpec);
    } catch (Exception ex) {
      player.sendMessage(componentService.builder(player, "template.invalid-regen-time", "", true).build());
      return 0;
    }

    Block target = player.getTargetBlockExact(6);
    if (target == null || target.getType().isAir()) {
      player.sendMessage(componentService.builder(player, "template.invalid-range", "", true).build());
      return 0;
    }

    File blocksDir = new File(regen.plugin().getDataFolder(), "blocks");
    if (!blocksDir.exists() && !blocksDir.mkdirs()) {
      player.sendMessage(componentService.builder(player, "", "<red>Could not create blocks folder: " + blocksDir.getPath(), true).build());
      return 0;
    }

    String fileName = normalizeBlockFileName(blockFile);
    File outFile = new File(blocksDir, fileName);

    Map<String, Object> newEntry;
    try {
      newEntry = templateService.buildEntryFromTemplate(target, templateId, regenTimeSpec);
    } catch (IllegalArgumentException ex) {
      player.sendMessage(componentService.builder(player, "template.invalid-template-id", "", true)
          .resolver(Placeholder.parsed("template-id", templateId))
          .build());
      return 0;
    }

    YamlConfiguration out = YamlConfiguration.loadConfiguration(outFile);

    if (!out.contains("enable")) out.set("enable", true);
    if (!out.contains("global-conditions")) out.set("global-conditions", out.getList("global-conditions", List.of()));

    List<Map<String, Object>> regenList = readRegenList(out);
    regenList.add(newEntry);
    out.set("regen", regenList);

    try {
      out.save(outFile);
    } catch (IOException e) {
      player.sendMessage(componentService.builder(player, "",
          "<red>Failed to save file <dark_red>" + outFile.getName() + " <red>See console log!", true)
          .build());
      e.printStackTrace();
      return 0;
    }

    try {
      regen.reloadEntries(loader.loadAll());
    } catch (Exception e) {
      player.sendMessage(componentService.builder(player, "template.not-loaded", "", true).build());
      e.printStackTrace();
      return 0;
    }

    TagResolver resolver = TagResolver.resolver(
        Placeholder.parsed("template-id", templateId),
        Placeholder.parsed("file-name", outFile.getName()),
        Placeholder.parsed("block", target.getType().getKey().toString())
    );

    player.sendMessage(componentService.builder(player, "template.add-success", "", true)
        .resolver(resolver)
        .build());

    return 1;
  }

  private static String normalizeBlockFileName(String input) {
    String s = input == null ? "" : input.trim();
    if (s.isEmpty()) return "blocks.yml";
    if (!s.toLowerCase(Locale.ROOT).endsWith(".yml") && !s.toLowerCase(Locale.ROOT).endsWith(".yaml")) {
      s = s + ".yml";
    }
    return s;
  }

  private static List<Map<String, Object>> readRegenList(YamlConfiguration out) {
    Object raw = out.get("regen");
    if (raw == null) return new ArrayList<>();
    if (raw instanceof List<?> list) {
      List<Map<String, Object>> res = new ArrayList<>();
      for (Object o : list) {
        if (o instanceof Map<?, ?> m) {
          Map<String, Object> mm = new LinkedHashMap<>();
          for (Map.Entry<?, ?> e : m.entrySet()) {
            mm.put(String.valueOf(e.getKey()), e.getValue());
          }
          res.add(mm);
        }
      }
      return res;
    }
    return new ArrayList<>();
  }
}