package io.nexstudios.nexregen.command;

import io.nexstudios.commandservice.service.commands.annotations.Arg;
import io.nexstudios.commandservice.service.commands.annotations.Command;
import io.nexstudios.commandservice.service.commands.annotations.CommandRoot;
import io.nexstudios.commandservice.service.commands.annotations.Suggest;
import io.nexstudios.commandservice.service.commands.source.NexPaperCommandSource;
import io.nexstudios.languageservice.service.component.ComponentService;
import io.nexstudios.nexregen.command.suggestions.BlockFileSuggestion;
import io.nexstudios.nexregen.service.manager.RegenManager;
import io.nexstudios.serviceregistry.di.Dependencies;
import io.nexstudios.serviceregistry.di.Service;
import io.nexstudios.serviceregistry.di.ServiceAccessor;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;

@CommandRoot(
    name = "nexregen",
    description = "NexRegen admin command"
)
@Dependencies({
    RegenManager.class,
    ComponentService.class
})
public class RegenRemoveCommand implements Service {

  private final RegenManager regen;
  private final ComponentService componentService;

  public RegenRemoveCommand(ServiceAccessor accessor) {
    this.regen = accessor.getService(RegenManager.class);
    this.componentService = accessor.getService(ComponentService.class);
  }

  @Command(value = "remove <block-file>", permission = "nexregen.admin")
  public int onRemove(
      NexPaperCommandSource source,
      @Arg("block-file") @Suggest(BlockFileSuggestion.class) String blockFile
  ) {
    Player player = (Player) source.sender();
    if (player == null) return 0;

    Block target = player.getTargetBlockExact(6);
    if (target == null || target.getType().isAir()) {
      player.sendMessage(componentService.builder(player, "template.invalid-range", "", true).build());
      return 0;
    }

    RegenManager.RemoveResult res;
    try {
      res = regen.removeRegenEntry(blockFile, target);
    } catch (Exception ex) {
      player.sendMessage(componentService.builder(
          player,
          "general.error",
          "NotDefined",
          true)
          .build());
      ex.printStackTrace();
      return 0;
    }

    TagResolver resolver = TagResolver.resolver(
        Placeholder.parsed("block", target.getType().getKey().toString()),
        Placeholder.parsed("file-name", res.fileName())
    );

    if (res.removed() <= 0) {

      player.sendMessage(componentService.builder(
          player,
          "template.block-not-found",
          "NotDefined",
          true)
          .resolver(resolver)
          .build());
      return 0;
    }

    player.sendMessage(componentService.builder(player, "template.remove-success",
        "NotDefined",
        true)
        .resolver(resolver)
        .build());

    return 1;
  }
}