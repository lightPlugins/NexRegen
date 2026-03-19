package io.nexstudios.nexregen.util;

import io.nexstudios.configservice.config.ConfigurationSection;
import io.nexstudios.nexlogic.bukkit.services.effects.context.BukkitContextKeys;
import io.nexstudios.nexlogic.common.effects.config.MapConfigSection;
import io.nexstudios.nexlogic.common.effects.model.LogicContext;
import io.nexstudios.nexlogic.common.services.conditions.ConditionAggregationMode;
import io.nexstudios.nexlogic.common.services.conditions.ConditionEvaluationResult;
import io.nexstudios.nexlogic.common.services.conditions.ConditionEvaluatorService;
import io.nexstudios.nexlogic.common.services.conditions.MissingCapabilityPolicy;
import io.nexstudios.nexlogic.common.services.triggers.schema.ContextCapability;
import io.nexstudios.serviceregistry.di.Dependencies;
import io.nexstudios.serviceregistry.di.ServiceAccessor;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;

import java.util.*;

@Dependencies({
    ConditionEvaluatorService.class
})
public final class NexLogicConditionFacade {

  private final ConditionEvaluatorService evaluator;

  public NexLogicConditionFacade(ServiceAccessor services) {
    this.evaluator = services.getService(ConditionEvaluatorService.class);
  }

  public boolean evaluateAll(List<ConfigurationSection> conditions, Block block, Player player, String contextName) {
    if (conditions == null || conditions.isEmpty()) return true;

    List<Map<String, Object>> conditionMaps = new ArrayList<>();
    for (ConfigurationSection sec : conditions) {
      Object raw = sec == null ? null : sec.node().raw();
      if (raw instanceof Map<?, ?> m) {
        conditionMaps.add(castStringObjectMap(m));
      } else {
        throw new IllegalStateException("Condition entry is not a map: " + raw);
      }
    }

    MapConfigSection cfg = new MapConfigSection(Map.of(
        "conditions", conditionMaps
    ));

    LogicContext ctx = new LogicContext(contextName);
    ctx.put(BukkitContextKeys.BLOCK, block);
    ctx.put(BukkitContextKeys.PLAYER, player);
    ctx.put(BukkitContextKeys.WORLD, block.getWorld());
    ctx.put(BukkitContextKeys.LOCATION, block.getLocation());

    ctx.declareCapabilities(
        ContextCapability.BLOCK,
        ContextCapability.PLAYER,
        ContextCapability.WORLD,
        ContextCapability.LOCATION
    );

    ConditionEvaluationResult res = evaluator.evaluateAt(
        cfg,
        "conditions",
        ctx,
        ConditionAggregationMode.ALL,
        MissingCapabilityPolicy.FAIL_FAST
    );
    return res.successFor(ConditionAggregationMode.ALL);
  }

  private static Map<String, Object> castStringObjectMap(Map<?, ?> input) {
    Map<String, Object> out = new LinkedHashMap<>();
    for (Map.Entry<?, ?> e : input.entrySet()) {
      out.put(String.valueOf(e.getKey()), e.getValue());
    }
    return out;
  }
}