package io.nexstudios.nexregen.command.suggestions;

import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import io.nexstudios.commandservice.service.commands.factory.suggest.SuggestionProvider;
import io.papermc.paper.command.brigadier.CommandSourceStack;

import java.util.List;
import java.util.concurrent.CompletableFuture;

public final class RegenTimeSuggestion implements SuggestionProvider {

  private static final List<String> SUGGESTIONS = List.of(
      "regen-time",
      "5",
      "10",
      "3-5",
      "10-20"
  );

  @Override
  public CompletableFuture<Suggestions> suggest(CommandContext<CommandSourceStack> ctx, SuggestionsBuilder builder) {
    String remaining = builder.getRemainingLowerCase();

    for (String s : SUGGESTIONS) {
      if (remaining.isEmpty() || s.startsWith(remaining)) {
        builder.suggest(s);
      }
    }
    return builder.buildFuture();
  }
}