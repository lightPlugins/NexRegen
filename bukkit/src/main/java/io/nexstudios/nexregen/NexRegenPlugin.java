package io.nexstudios.nexregen;

import io.nexstudios.framework.paper.NexPaperPlugin;
import io.nexstudios.serviceregistry.di.ServiceAccessor;
import org.jetbrains.annotations.NotNull;

public class NexRegenPlugin extends NexPaperPlugin {


  @Override
  protected void configureServices(@NotNull ServiceAccessor services) {

  }


  @Override
  protected void load() {
    getLogger().info("NexLogic is loading...");
  }

  @Override
  protected void start() {
    getLogger().info("NexLogic is starting...");
  }

  @Override
  protected void stop() {
    getLogger().info("NexLogic is stopping...");
  }

}
