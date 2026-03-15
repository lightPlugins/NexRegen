package io.nexstudios.nexregen;

import io.nexstudios.commandservice.CommandServiceModule;
import io.nexstudios.configservice.ConfigServiceModule;
import io.nexstudios.framework.paper.NexPaperPlugin;
import io.nexstudios.itemservice.bukkit.ItemServiceModule;
import io.nexstudios.languageservice.LanguageServiceModule;
import io.nexstudios.menuservice.bukkit.service.menu.MenuServiceModule;
import io.nexstudios.nexlogic.bukkit.NexLogicPlugin;
import io.nexstudios.nexlogic.bukkit.services.effects.logging.BukkitLoggerService;
import io.nexstudios.nexlogic.common.services.logging.LoggerService;
import io.nexstudios.nexregen.service.NexLogicConditionFacade;
import io.nexstudios.nexregen.service.RegenConfigLoader;
import io.nexstudios.nexregen.service.RegenManager;
import io.nexstudios.nexregen.service.listener.RegenBlockBreakListener;
import io.nexstudios.serviceregistry.di.ServiceAccessor;
import io.nexstudios.serviceregistry.di.ServiceModule;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginManager;
import org.jetbrains.annotations.NotNull;

import java.util.List;

public class NexRegenPlugin extends NexPaperPlugin {

  private static NexLogicPlugin nexLogicPlugin;
  private static ServiceAccessor nexLogicService;

  private RegenManager regenManager;

  @Override
  protected void configureServices(@NotNull ServiceAccessor services) {

    // install ConfigService
    services.install(new ConfigServiceModule(getDataPath(), getClassLoader()));
    // install LanguageService (require ConfigService loaded)
    services.install(new LanguageServiceModule(this));
    // install ItemService
    services.install(new ItemServiceModule(this));
    // install MenuService
    services.install(new MenuServiceModule(this));
    // install Command Service
    services.install(new CommandServiceModule(this));

    // install internal ServiceModules
    List<ServiceModule> modules = List.of();
    services.installAll(modules);

    services.register(LoggerService.class, BukkitLoggerService.class);
  }

  @Override
  protected void load() {
    getLogger().info("NexRegen is loading...");
  }

  @Override
  protected void start() {
    initNexLogic();

    RegenConfigLoader loader = new RegenConfigLoader(services());
    services().register(RegenConfigLoader.class, loader);
    NexLogicConditionFacade conditionFacade = new NexLogicConditionFacade(nexLogicService);

    this.regenManager = new RegenManager(this, loader.loadAll(), conditionFacade);
    services().register(RegenManager.class, regenManager);

    PluginManager pm = Bukkit.getPluginManager();
    pm.registerEvents(new RegenBlockBreakListener(services()), this);

    getLogger().info("NexRegen started.");
  }

  @Override
  protected void stop() {
    if (this.regenManager != null) {
      this.regenManager.forceCompleteAllPendingOnShutdown();
    }
    getLogger().info("NexRegen stopped.");
  }

  private void initNexLogic() {
    Plugin plugin = Bukkit.getPluginManager().getPlugin("NexLogic");
    if (plugin == null || !plugin.isEnabled()) {
      throw new IllegalStateException("Could not find NexLogic plugin! Please install it!");
    }

    if (!(plugin instanceof NexLogicPlugin nexLogic)) {
      throw new IllegalStateException("Plugin 'NexLogic' is not a NexLogicPlugin: " + plugin.getClass().getName());
    }

    nexLogicPlugin = nexLogic;
    nexLogicService = nexLogic.services();
    getLogger().info("Successfully hooked into NexLogic!");
  }
}