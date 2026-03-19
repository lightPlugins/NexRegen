package io.nexstudios.nexregen;

import io.nexstudios.commandservice.CommandServiceModule;
import io.nexstudios.commandservice.service.commands.CommandService;
import io.nexstudios.configservice.ConfigServiceModule;
import io.nexstudios.configservice.service.singlereader.FileReaderService;
import io.nexstudios.framework.paper.NexPaperPlugin;
import io.nexstudios.itemservice.bukkit.ItemServiceModule;
import io.nexstudios.languageservice.LanguageServiceModule;
import io.nexstudios.languageservice.service.language.LanguageService;
import io.nexstudios.nexlogic.bukkit.NexLogicPlugin;
import io.nexstudios.nexlogic.bukkit.services.effects.logging.BukkitLoggerService;
import io.nexstudios.nexlogic.common.services.logging.LoggerService;
import io.nexstudios.nexregen.command.RegenAddCommand;
import io.nexstudios.nexregen.command.RegenReloadCommand;
import io.nexstudios.nexregen.command.RegenRemoveCommand;
import io.nexstudios.nexregen.service.config.RegenConfigLoader;
import io.nexstudios.nexregen.service.listener.RegenBlockInteractListener;
import io.nexstudios.nexregen.service.manager.RegenManager;
import io.nexstudios.nexregen.service.listener.RegenBlockBreakListener;
import io.nexstudios.nexregen.service.listener.RegenFakeViewInteractListener;
import io.nexstudios.nexregen.service.listener.RegenMiningBlockListener;
import io.nexstudios.nexregen.service.template.RegenTemplateService;
import io.nexstudios.nexregen.util.NexLogicConditionFacade;
import io.nexstudios.serviceregistry.di.ServiceAccessor;
import io.nexstudios.serviceregistry.di.ServiceModule;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginManager;
import org.jetbrains.annotations.NotNull;

import java.nio.file.Path;
import java.util.List;

public class NexRegenPlugin extends NexPaperPlugin {

  private static ServiceAccessor nexLogicService;

  @Override
  protected void configureServices(@NotNull ServiceAccessor services) {

    // install ConfigService
    services.install(new ConfigServiceModule(getDataPath(), getClassLoader()));
    // install LanguageService (require ConfigService loaded)
    services.install(new LanguageServiceModule(this));
    // install ItemService
    services.install(new ItemServiceModule(this));
    // install Command Service
    services.install(new CommandServiceModule(this));

    services.register(LoggerService.class, BukkitLoggerService.class);

    // install internal ServiceModules
    List<ServiceModule> modules = List.of();
    services.installAll(modules);
  }

  @Override
  protected void load() {
    getLogger().info("NexRegen is loading...");
  }

  @Override
  protected void start() {
    getLogger().info("NexRegen is starting...");
    initNexLogic();

    // init language files
    services().getService(LanguageService.class).reload();

    getLogger().info("Read existing regen configs...");
    RegenConfigLoader loader = new RegenConfigLoader(services());
    services().register(RegenConfigLoader.class, loader);
    NexLogicConditionFacade conditionFacade = new NexLogicConditionFacade(nexLogicService);

    getLogger().info("Load regen configs...");
    FileReaderService fileReader = services().getService(FileReaderService.class);
    fileReader.load(
        Path.of("templates.yml"),
        "templates.yml",
        true
    );

    RegenManager regenManager = new RegenManager(this, loader, conditionFacade);
    services().register(RegenManager.class, regenManager);
    services().register(RegenTemplateService.class, RegenTemplateService.class);

    // register listeners
    getLogger().info("Registering listeners...");
    PluginManager pm = Bukkit.getPluginManager();
    pm.registerEvents(new RegenMiningBlockListener(services()), this);
    pm.registerEvents(new RegenBlockBreakListener(services()), this);
    pm.registerEvents(new RegenFakeViewInteractListener(services()), this);
    pm.registerEvents(new RegenBlockInteractListener(services()), this);

    // register commands
    getLogger().info("Registering commands...");
    services().getService(CommandService.class).registerAll(
        List.of(
            RegenReloadCommand.class,
            RegenAddCommand.class,
            RegenRemoveCommand.class
        )
    );

    getLogger().info("NexRegen successfully started.");
  }

  @Override
  protected void stop() {
    getLogger().info("NexRegen stopped.");
  }

  private void initNexLogic() {
    getLogger().info("Hooking into NexLogic...");
    Plugin plugin = Bukkit.getPluginManager().getPlugin("NexLogic");
    if (plugin == null || !plugin.isEnabled()) {
      throw new IllegalStateException("Could not find NexLogic plugin! Please install it!");
    }

    if (!(plugin instanceof NexLogicPlugin nexLogic)) {
      throw new IllegalStateException("Plugin 'NexLogic' is not a NexLogicPlugin: " + plugin.getClass().getName());
    }

    nexLogicService = nexLogic.services();
    getLogger().info("Successfully hooked into NexLogic!");
  }
}