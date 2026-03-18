package io.nexstudios.nexregen.command.suggestions;

import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import io.nexstudios.commandservice.service.commands.factory.suggest.SuggestionProvider;
import io.nexstudios.nexregen.service.template.RegenTemplateService;
import io.papermc.paper.command.brigadier.CommandSourceStack;

import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

public final class TemplateIdSuggestion implements SuggestionProvider {

  @Override
  public CompletableFuture<Suggestions> suggest(CommandContext<CommandSourceStack> ctx, SuggestionsBuilder builder) {
    String remaining = builder.getRemainingLowerCase();

    Set<String> ids = RegenTemplateService.cachedTemplateIds();
    for (String id : ids) {
      if (id == null) continue;
      String lower = id.toLowerCase(Locale.ROOT);
      if (remaining.isEmpty() || lower.startsWith(remaining)) {
        builder.suggest(id);
      }
    }

    return builder.buildFuture();
  }
}