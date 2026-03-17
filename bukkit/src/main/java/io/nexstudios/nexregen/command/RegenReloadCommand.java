package io.nexstudios.nexregen.command;

import io.nexstudios.commandservice.service.commands.annotations.Command;
import io.nexstudios.commandservice.service.commands.annotations.CommandRoot;
import io.nexstudios.commandservice.service.commands.source.NexPaperCommandSource;
import io.nexstudios.languageservice.service.component.ComponentService;
import io.nexstudios.languageservice.service.language.LanguageService;
import io.nexstudios.nexregen.service.RegenConfigLoader;
import io.nexstudios.nexregen.service.RegenManager;
import io.nexstudios.serviceregistry.di.Dependencies;
import io.nexstudios.serviceregistry.di.Service;
import io.nexstudios.serviceregistry.di.ServiceAccessor;
import org.bukkit.entity.Player;

@CommandRoot(
    name = "nexregen",
    description = "NexRegen admin command"
)
@Dependencies({
    RegenConfigLoader.class,
    RegenManager.class,
    ComponentService.class,
    LanguageService.class
})
public final class RegenReloadCommand implements Service {

  private final RegenConfigLoader loader;
  private final RegenManager regen;
  private final ComponentService componentService;
  private final LanguageService languageService;

  public RegenReloadCommand(ServiceAccessor accessor) {
    this.loader = accessor.getService(RegenConfigLoader.class);
    this.regen = accessor.getService(RegenManager.class);
    this.componentService = accessor.getService(ComponentService.class);
    this.languageService = accessor.getService(LanguageService.class);
  }

  @Command(value = "reload", permission = "nexregen.admin")
  public int reload(NexPaperCommandSource source) {
    Player player = (Player) source.sender();
    if(player == null) { return 0; }

    languageService.reload();

    try {
      var entries = loader.loadAll();
      regen.reloadEntries(entries);

      player.sendMessage(componentService.builder(player, "general.reload", "NotDefined").build());
      return 1;
    } catch (Exception e) {
      player.sendMessage(componentService.builder(player, "general.reload-failed", "NotDefined").build());
      e.printStackTrace();
      return 0;
    }
  }
}